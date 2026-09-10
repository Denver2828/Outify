use once_cell::sync::OnceCell;
use reqwest::Client;
use std::{
    sync::{
        Arc, Mutex,
        atomic::{AtomicI64, AtomicU64, Ordering},
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::sync::RwLock;

use crate::spotify::error::SpotifyApiError;

use auth::TokenCache;

mod auth;
mod library;
mod player;
mod playlist;
mod user;

#[cfg(test)]
mod admission_tests;

pub use library::SavedItemType;

const SPOTIFY_API_URL: &str = "https://api.spotify.com";
const REQUEST_TIMEOUT: Duration = Duration::from_secs(5);
const SPOTIFY_OAUTH_CALLBACK_URI: &str = "http://127.0.0.1:5588/account/login";
const SPOTIFY_OAUTH_SCOPES: &[&str] = &[
    "streaming",
    "user-read-private",
    "user-read-email",
    "user-top-read",
    "user-library-modify",
    "user-library-read",
    "user-follow-modify",
    "user-read-playback-state",
    "playlist-modify-private",
    "playlist-modify-public",
];

static SPOTIFY_CLIENT: OnceCell<SpotifyClient> = OnceCell::new();

/// Wall-clock millisecond timestamp until which the Web API must not be called
/// (0 when no 429 is pending). Kotlin reads it through `SpClient.getRateLimitUntilMs`.
static RATE_LIMIT_UNTIL_MS: AtomicI64 = AtomicI64::new(0);
static RATE_LIMIT_OBSERVER: OnceCell<Box<dyn Fn(i64) + Send + Sync>> = OnceCell::new();
static REQUEST_ADMISSION: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

/// Serialize dispatch through headers, not bodies or token acquisition. Token refresh
/// also uses this boundary: its 429 contributes to the same native deadline.
pub(super) trait GatedRequest {
    async fn send_gated(
        self,
        method: &str,
        bypass: bool,
    ) -> Result<reqwest::Response, SpotifyApiError>;
}

impl GatedRequest for reqwest::RequestBuilder {
    async fn send_gated(
        self,
        method: &str,
        bypass: bool,
    ) -> Result<reqwest::Response, SpotifyApiError> {
        let _admission = REQUEST_ADMISSION.lock().await;
        if !bypass {
            check_rate_limit(method)?;
        }
        let response = self.send().await?;
        if response.status() == reqwest::StatusCode::TOO_MANY_REQUESTS {
            note_rate_limited(retry_after_secs(&response));
        }
        Ok(response)
    }
}

const DEFAULT_RETRY_AFTER_SECS: u64 = 30;

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// Records a 429 so every caller (native and Kotlin) can hold off until it expires.
pub(crate) fn note_rate_limited(retry_after_secs: u64) {
    let until = now_ms() + (retry_after_secs as i64) * 1000;
    extend_rate_limit(until);
    warn!("spotify web api rate limited for {retry_after_secs} s");
}

fn extend_rate_limit(until: i64) {
    let previous = RATE_LIMIT_UNTIL_MS.fetch_max(until, Ordering::AcqRel);
    if until > previous {
        if let Some(notify) = RATE_LIMIT_OBSERVER.get() {
            // Notification failure must never unwind across JNI or discard native authority.
            if std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| notify(until))).is_err() {
                error!("rate-limit observer panicked");
            }
        }
    }
}

/// Timestamp (ms since epoch) until which the Web API is rate limited, 0 when it is not.
pub fn rate_limit_until_ms() -> i64 {
    let until = RATE_LIMIT_UNTIL_MS.load(Ordering::Acquire);
    if until > now_ms() { until } else { 0 }
}

/// Gate every Web API helper runs before sending. While the shared 429 window is still
/// open it fails with the same [`SpotifyApiError::RateLimited`] a real 429 produces
/// (remaining whole seconds, at least 1) and no request goes out. The diagnostics probe
/// is the only caller allowed to bypass it.
pub(crate) fn check_rate_limit(method: &str) -> Result<(), SpotifyApiError> {
    let remaining_ms = RATE_LIMIT_UNTIL_MS.load(Ordering::Acquire) - now_ms();
    if remaining_ms <= 0 {
        return Ok(());
    }
    let retry_after_secs = ((remaining_ms + 999) / 1000).max(1) as u64;
    debug!("{method} skipped: rate limited for {retry_after_secs} s");
    Err(SpotifyApiError::RateLimited {
        retry_after_secs,
        body: "request skipped, rate-limit window still open".to_string(),
    })
}

/// Parses `Retry-After` as delay seconds; HTTP dates are not expected from Spotify.
fn retry_after_secs(res: &reqwest::Response) -> u64 {
    res.headers()
        .get(reqwest::header::RETRY_AFTER)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.trim().parse::<u64>().ok())
        .map(|v| v.max(1))
        .unwrap_or(DEFAULT_RETRY_AFTER_SECS)
}

/// Turns a non-2xx response into the matching error. 429 becomes
/// [`SpotifyApiError::RateLimited`]; `send_gated` already armed the window at headers. Every
/// other status keeps the historical `{method} failed with status ...` message.
pub(crate) async fn ensure_success(
    method: &str,
    res: reqwest::Response,
) -> Result<reqwest::Response, SpotifyApiError> {
    let status = res.status();
    if status.is_success() {
        return Ok(res);
    }
    if status == reqwest::StatusCode::TOO_MANY_REQUESTS {
        let retry_after = retry_after_secs(&res);
        let body = res.text().await.unwrap_or_default();
        error!("{method} rate limited (retry after {retry_after} s): {body}");
        return Err(SpotifyApiError::RateLimited {
            retry_after_secs: retry_after,
            body,
        });
    }
    let body = res.text().await.unwrap_or_default();
    Err(SpotifyApiError::Generic(format!(
        "{method} failed with status {}: {body}",
        status.as_str()
    )))
}

/// OAuth state for SpotifyClient's user authentication flow
pub struct OAuthState {
    pub oauth_client: librespot_oauth::OAuthClient,
    pub pkce_verifier: Option<oauth2::PkceCodeVerifier>,
    pub created_at: Instant,
}

pub struct SpotifyClient {
    pub(crate) client_id: Mutex<String>,
    pub(crate) client_secret: Mutex<String>,
    pub(crate) client: Client,
    /// In-memory Web API token plus the refresh negative cache. The async mutex is held
    /// across the refresh POST so at most one refresh is in flight; account.json stays
    /// the source of truth across processes and is read only when this copy is empty.
    pub(crate) token: tokio::sync::Mutex<TokenCache>,
    /// Bumped whenever the cached token must be dropped from a synchronous path
    /// (credential switch, logout); the async paths compare it before trusting the cache.
    pub(crate) token_generation: AtomicU64,
    pub(crate) oauth_state: Arc<RwLock<Option<OAuthState>>>,
}

impl SpotifyClient {
    pub fn new(client_id: String, client_secret: String) -> Self {
        Self {
            client_id: Mutex::new(client_id),
            client_secret: Mutex::new(client_secret),
            client: Client::builder()
                .pool_idle_timeout(Duration::from_secs(90))
                .build()
                .expect("failed to build client"),
            token: tokio::sync::Mutex::new(TokenCache::default()),
            token_generation: AtomicU64::new(0),
            oauth_state: Arc::new(RwLock::new(None)),
        }
    }

    pub fn update_credentials(&self, client_id: String, client_secret: String) {
        *self.client_id.lock().unwrap() = client_id;
        *self.client_secret.lock().unwrap() = client_secret;
        // A refresh that failed with the old client id must be retried with the new one.
        self.invalidate_token_cache();
    }

    /// Drops the in-memory token and the refresh negative cache on the next async access.
    /// Safe from synchronous JNI paths: no lock is taken here.
    pub(crate) fn invalidate_token_cache(&self) {
        self.token_generation.fetch_add(1, Ordering::AcqRel);
    }
}

pub(crate) async fn check_response_json<T: serde::de::DeserializeOwned>(
    method: &str,
    res: reqwest::Response,
) -> Result<T, SpotifyApiError> {
    let res = ensure_success(method, res).await?;
    let text = res.text().await?;
    let data = serde_json::from_str(&text)?;
    Ok(data)
}

pub fn init_client(
    client_id: String,
    client_secret: String,
    saved_until: i64,
    observer: Box<dyn Fn(i64) + Send + Sync>,
) {
    let _ = RATE_LIMIT_OBSERVER.set(observer);
    // Restore shared authority before any caller can obtain the client.
    extend_rate_limit(saved_until);
    let client = SpotifyClient::new(client_id, client_secret);
    let _ = SPOTIFY_CLIENT.set(client);
}

pub fn get_client() -> &'static SpotifyClient {
    SPOTIFY_CLIENT
        .get()
        .expect("SpotifyClient not initialized!")
}

pub fn update_client(client_id: String, client_secret: String) {
    if let Some(client) = SPOTIFY_CLIENT.get() {
        client.update_credentials(client_id, client_secret);
        info!("spotify client credentials updated");
    }
}
