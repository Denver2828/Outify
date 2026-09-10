use jni::JavaVM;
use log::{Level, LevelFilter, Metadata, Record};
#[cfg(target_os = "android")]
use std::ffi::CString;

#[cfg(target_os = "android")]
const ANDROID_LOG_VERBOSE: i32 = 2;
#[cfg(target_os = "android")]
const ANDROID_LOG_DEBUG: i32 = 3;
#[cfg(target_os = "android")]
const ANDROID_LOG_INFO: i32 = 4;
#[cfg(target_os = "android")]
const ANDROID_LOG_WARN: i32 = 5;
#[cfg(target_os = "android")]
const ANDROID_LOG_ERROR: i32 = 6;

#[cfg(target_os = "android")]
unsafe extern "C" {
    fn __android_log_write(prio: i32, tag: *const i8, text: *const i8) -> i32;
}

pub struct AndroidLogger {
    #[allow(dead_code)]
    jvm: JavaVM,
}

impl AndroidLogger {
    /// Initialize the global logger.
    pub fn init(jvm: JavaVM, level: LevelFilter) -> Result<(), log::SetLoggerError> {
        let logger = AndroidLogger { jvm };
        log::set_boxed_logger(Box::new(logger))?;
        log::set_max_level(level);
        Ok(())
    }
}

#[cfg(target_os = "android")]
fn log_to_ndk(level: Level, tag: &str, msg: &str) {
    let tag = if tag.is_empty() { "rust" } else { tag };
    let tag_c = CString::new(tag).unwrap_or_else(|_| CString::new("rust").unwrap());
    // if message contains NULs, fall back to a safe placeholder
    let msg_c = CString::new(msg).unwrap_or_else(|_| CString::new("invalid log message").unwrap());

    let prio = match level {
        Level::Error => ANDROID_LOG_ERROR,
        Level::Warn => ANDROID_LOG_WARN,
        Level::Info => ANDROID_LOG_INFO,
        Level::Debug => ANDROID_LOG_DEBUG,
        Level::Trace => ANDROID_LOG_VERBOSE,
    };

    unsafe {
        let _ = __android_log_write(
            prio,
            tag_c.as_ptr() as *const i8,
            msg_c.as_ptr() as *const i8,
        );
    }
}

/// Host tests use stderr without linking Android's platform logging library.
#[cfg(not(target_os = "android"))]
fn log_to_ndk(level: Level, tag: &str, msg: &str) {
    use std::io::Write;
    let tag = if tag.is_empty() { "rust" } else { tag };
    let _ = writeln!(std::io::stderr(), "{level} {tag}: {msg}");
}

impl log::Log for AndroidLogger {
    fn enabled(&self, _metadata: &Metadata) -> bool {
        true
    }

    fn log(&self, record: &Record) {
        if !self.enabled(record.metadata()) {
            return;
        }

        let tag = record.target();
        let msg = format!("{}", record.args());
        log_to_ndk(record.level(), tag, &msg);
    }

    fn flush(&self) {}
}
