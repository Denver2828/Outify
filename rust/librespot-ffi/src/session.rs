use std::{
    pin::Pin, sync::{
        RwLock, atomic::{AtomicBool, Ordering},
    },
};

use crate::{CACHE_DIR, FILES_DIR, TOKIO_RUNTIME};
use jni::objects::GlobalRef;
use librespot_core::{Session, SessionConfig, cache::Cache, config::KEYMASTER_CLIENT_ID};
use once_cell::sync::OnceCell;

pub static SESSION: OnceCell<RwLock<Option<Session>>> = OnceCell::new();
pub static SESSION_CALLBACK: OnceCell<RwLock<Option<GlobalRef>>> = OnceCell::new();
static IS_AUTO_RESTARTING: AtomicBool = AtomicBool::new(false);
const MAX_RESTART_ATTEMPTS: u32 = 8;
const MAX_RESTART_BACKOFF_SECS: u64 = 30;

pub fn init_session_container() {
    SESSION.get_or_init(|| RwLock::new(None));
    SESSION_CALLBACK.get_or_init(|| RwLock::new(None));
}

// Initializes the session work further usage
pub async fn initialize_session() {
    let container = SESSION.get().expect("Session container not initialized");

    {
        let guard = container.read().unwrap();
        if guard.is_some() {
            warn!("session container already has a session");
        }
    }

    let rt = match TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            warn!("tokio runtime not available for session init");
            return;
        }
    };

    // Geting session cache dir
    let os_cache_dir = match CACHE_DIR.get() {
        Some(dir) => dir.to_path_buf(),
        None => {
            error!("cache dir not set, call libInit first");
            return;
        }
    };
    let os_files_dir = match FILES_DIR.get() {
        Some(dir) => dir.to_path_buf(),
        None => {
            error!("files dir not set, call libInit first");
            return;
        }
    };
    let cache: Cache = Cache::new(Some(&os_files_dir), None, Some(&os_cache_dir), None).unwrap();
    trace!("cache initialized");

    let handle = rt.handle().clone();
    let session_config = SessionConfig {
        client_id: KEYMASTER_CLIENT_ID.to_owned(),
        ..Default::default()
    };
    let session = Session::with_handle(session_config, Some(cache), handle);

    let mut guard = container.write().unwrap();
    *guard = Some(session.clone());

    start_shutdown_listener(session);
    debug!("session initialized");
}

// Connects the already initialized session
pub async fn connect() -> Result<Session, librespot_core::Error> {
    let session = match with_session(|s| s.clone()) {
        Ok(s) => s,
        Err(e) => {
            error!("failed to clone session for connect: {e}");
            return Err(librespot_core::Error::internal("failed to clone session for connect"));
        }
    };

    let credentials = session
        .cache()
        .and_then(|cache| cache.credentials())
        .ok_or_else(|| {
            warn!("no cached credentials for connect");
            librespot_core::Error::unauthenticated("No cached credentials available".to_string())
        })?;

    session.connect(credentials, false).await.map_err(|e| {
        error!("session connect failed: {e}");
        e
    })?;

    debug!("session connected");
    Ok(session.clone())
}

// Listens for session shutdowns
fn start_shutdown_listener(session: Session) {
    let rt = match TOKIO_RUNTIME.get() {
        Some(r) => r,
        None => {
            warn!("tokio runtime not available for shutdown listener");
            return;
        }
    };

    rt.handle().spawn(async move {
        let mut shutdown_rx = session.subscribe_shutdown();
        shutdown_rx.changed().await.ok();

        if IS_AUTO_RESTARTING.swap(true, Ordering::Acquire) {
            warn!("auto-restart already in progress, skipping");
            return;
        }

        notify_callback("onShutdown".to_string());

        cleanup().await;

        warn!("session disconnected, auto-restarting");

        let device_name = crate::spirc::DEVICE_NAME
            .get()
            .map(|m| m.lock().unwrap().clone())
            .unwrap_or("Outify".to_string());
        let gapless = crate::spirc::GAPLESS.load(std::sync::atomic::Ordering::Relaxed);
        let normalise = crate::spirc::NORMALISE_AUDIO.load(std::sync::atomic::Ordering::Relaxed);
        let bitrate_mutex = crate::spirc::BITRATE
            .get()
            .expect("BITRATE not initialized");
        let bitrate = *bitrate_mutex.lock().unwrap();

        // A reconnect right after a network drop usually fails on DNS or on the access point;
        // retry with a bounded backoff instead of giving up on the first error, which used
        // to leave the app without a session until the next manual restart.
        let mut attempt: u32 = 0;
        loop {
            attempt += 1;
            initialize_session().await;
            match crate::spirc::initialize_spirc(device_name.clone(), gapless, normalise, bitrate)
                .await
            {
                Ok(()) => break,
                Err(e) => {
                    error!("spirc init after reconnect failed (attempt {attempt}/{MAX_RESTART_ATTEMPTS}): {e}");
                    if attempt >= MAX_RESTART_ATTEMPTS {
                        IS_AUTO_RESTARTING.store(false, Ordering::Release);
                        error!("auto-restart gave up; a manual restart is required");
                        return;
                    }
                    cleanup().await;
                    let delay_secs = (2u64.pow(attempt)).min(MAX_RESTART_BACKOFF_SECS);
                    warn!("retrying session restart in {delay_secs} s");
                    tokio::time::sleep(std::time::Duration::from_secs(delay_secs)).await;
                }
            }
        }
        let _ = crate::spirc::with_spirc(|spirc| {
            info!("auto-transferring session after reconnect");
            let _ = spirc.activate();
            let _ = spirc.transfer();
            spirc.resume_playback();
        });

        notify_callback("onAutoRestart".to_string());

        IS_AUTO_RESTARTING.store(false, Ordering::Release);
    });
}

fn notify_callback(method: String) {
    let jvm = match crate::JVM.get() {
        Some(j) => j,
        None => {
            error!("jvm not available for session callback");
            return;
        }
    };

    let mut env = match jvm.attach_current_thread() {
        Ok(e) => e,
        Err(e) => {
            error!("thread attach for session callback failed: {e}");
            return;
        }
    };

    if let Some(lock) = SESSION_CALLBACK.get() {
        let guard = lock.read().unwrap();

        if let Some(callback) = &*guard {
            env.call_method(callback.as_obj(), method, "()V", &[]).ok();
        }
    }
}

// Sets the SessionCallback
pub fn set_session_callback(global: GlobalRef) {
    if let Some(lock) = SESSION_CALLBACK.get() {
        let mut guard = lock.write().unwrap();
        *guard = Some(global);
    }
}

pub fn unregister_session_callback() {
    if let Some(lock) = SESSION_CALLBACK.get() {
        let mut guard = lock.write().unwrap();
        if let Some(global) = guard.take() {
            drop(global);
        }
    }
}

async fn cleanup() {
    if let Some(lock) = SESSION.get() {
        let mut guard = lock.write().unwrap();
        guard.take();
    }

    // `crate::spirc::shutdown()` takes the runtime lock itself. Calling a method that needs
    // the write lock from inside `with_spirc` (which holds the read lock) deadlocked the
    // auto-restart task forever, leaving the app without a session after any network drop.
    crate::spirc::shutdown();
}

/// Username of the connected session, `None` while there is no session (e.g. during a
/// reconnect). Never panics: this runs on JNI threads where a panic aborts the whole app.
pub fn get_username() -> Option<String> {
    match with_session(|session| session.username()) {
        Ok(name) => Some(name),
        Err(e) => {
            warn!("username unavailable: {e}");
            None
        }
    }
}

// Helper function to retrieve &Session
pub fn with_session<F, R>(f: F) -> Result<R, librespot_core::Error>
where
    F: FnOnce(&Session) -> R,
{
    let container = SESSION
        .get()
        .ok_or_else(|| librespot_core::Error::internal("Session container not initialized"))?;

    let guard = container.read().unwrap();

    let session = guard
        .as_ref()
        .ok_or_else(|| librespot_core::Error::internal("Session not created"))?;

    Ok(f(session))
}

pub async fn with_session_async<F, R>(f: F) -> Result<R, librespot_core::Error>
where
    for<'s> F: FnOnce(&'s librespot_core::Session) -> Pin<Box<dyn Future<Output = R> + 's>>,
{
    let container = SESSION
        .get()
        .ok_or_else(|| librespot_core::Error::internal("Session container not initialized"))?;

    let guard = container.read().unwrap();

    let session = guard
        .as_ref()
        .ok_or_else(|| librespot_core::Error::internal("Session not created"))?;

    Ok(f(session).await)
}
