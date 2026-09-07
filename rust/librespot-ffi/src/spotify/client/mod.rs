use once_cell::sync::OnceCell;
use reqwest::Client;
use std::{
    sync::{
        Arc, Mutex,
        atomic::{AtomicI64, Ordering},
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::sync::RwLock;

use crate::spotify::{error::SpotifyApiError, token::WebApiToken};

mod auth;
mod library;
mod player;
mod playlist;
mod user;

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
    RATE_LIMIT_UNTIL_MS.fetch_max(until, Ordering::Release);
    warn!("spotify web api rate limited for {retry_after_secs} s");
}

/// Timestamp (ms since epoch) until which the Web API is rate limited, 0 when it is not.
pub fn rate_limit_until_ms() -> i64 {
    let until = RATE_LIMIT_UNTIL_MS.load(Ordering::Acquire);
    if until > now_ms() { until } else { 0 }
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
/// [`SpotifyApiError::RateLimited`] and arms the shared rate-limit window; every
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
        note_rate_limited(retry_after);
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
    pub(crate) token: Arc<RwLock<Option<WebApiToken>>>,
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
            token: Arc::new(RwLock::new(None)),
            oauth_state: Arc::new(RwLock::new(None)),
        }
    }

    pub fn update_credentials(&self, client_id: String, client_secret: String) {
        *self.client_id.lock().unwrap() = client_id;
        *self.client_secret.lock().unwrap() = client_secret;
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

pub fn init_client(client_id: String, client_secret: String) {
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
