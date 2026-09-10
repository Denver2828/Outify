use std::{
    collections::HashMap,
    fs::OpenOptions,
    os::unix::fs::OpenOptionsExt,
    sync::atomic::Ordering,
    time::{Duration, Instant},
};

use librespot_oauth::{OAuthClientBuilder, OAuthToken};
use oauth2::AuthorizationCode;

use crate::spotify::{
    error::SpotifyApiError,
    token::{TokenResponse, WebApiToken},
};

use super::{
    check_response_json, OAuthState, SpotifyClient, SPOTIFY_OAUTH_CALLBACK_URI,
    SPOTIFY_OAUTH_SCOPES,
};

/// How long a non-network refresh failure (HTTP 400/401: invalid_client, invalid_grant)
/// is answered from memory before the token endpoint is asked again.
const REFRESH_FAILURE_TTL: Duration = Duration::from_secs(120);

/// In-memory copy of the Web API token, guarded by [`SpotifyClient::token`].
#[derive(Default)]
pub(crate) struct TokenCache {
    token: Option<WebApiToken>,
    /// Message of the last non-network refresh failure and when it stops being replayed.
    refresh_failure: Option<(Instant, String)>,
    /// [`SpotifyClient::token_generation`] value this cache was filled under.
    generation: u64,
}

impl TokenCache {
    /// Drops everything when a synchronous path (credential switch, logout) bumped the
    /// generation since this cache was filled.
    fn sync_generation(&mut self, current: u64) {
        if self.generation != current {
            self.token = None;
            self.refresh_failure = None;
            self.generation = current;
        }
    }

    fn replace(&mut self, token: WebApiToken, generation: u64) {
        self.token = Some(token);
        self.refresh_failure = None;
        self.generation = generation;
    }
}

impl SpotifyClient {
    pub async fn get_oauth_url(&self) -> String {
        SPOTIFY_OAUTH_CALLBACK_URI.to_string()
    }

    pub async fn start_oauth_flow(&self) -> Result<String, SpotifyApiError> {
        let client_id = self.client_id.lock().unwrap().clone();
        let oauth_client = OAuthClientBuilder::new(
            &client_id,
            SPOTIFY_OAUTH_CALLBACK_URI,
            SPOTIFY_OAUTH_SCOPES.to_vec(),
        )
        .build()
        .map_err(|e| SpotifyApiError::Generic(format!("Failed to build OAuth client: {}", e)))?;

        let (auth_url, pkce_verifier) = oauth_client.set_auth_url();

        let state = OAuthState {
            oauth_client,
            pkce_verifier: Some(pkce_verifier),
            created_at: Instant::now(),
        };

        let mut oauth_state_guard = self.oauth_state.write().await;
        *oauth_state_guard = Some(state);

        debug!("oauth flow started with url: {auth_url}");
        Ok(auth_url.to_string())
    }

    pub async fn complete_oauth_flow(&self, code: String) -> Result<WebApiToken, SpotifyApiError> {
        let mut oauth_state_guard = self.oauth_state.write().await;
        let state = oauth_state_guard.as_mut().ok_or(SpotifyApiError::Generic(
            "OAuth flow not started. Call start_oauth_flow first.".to_string(),
        ))?;

        if state.created_at.elapsed() > Duration::from_secs(600) {
            error!("oauth state expired");
            return Err(SpotifyApiError::Generic(
                "OAuth state expired. Please restart the flow.".to_string(),
            ));
        }

        let pkce_verifier = state.pkce_verifier.take().ok_or(SpotifyApiError::Generic(
            "PKCE verifier not found. OAuth flow may have already completed.".to_string(),
        ))?;
        let oauth_client = &state.oauth_client;

        let auth_code = AuthorizationCode::new(code);
        let token_response: OAuthToken = oauth_client
            .get_access_token_with_verifier_async(pkce_verifier, auth_code)
            .await
            .map_err(|e| {
                error!("oauth token exchange failed: {e}");
                SpotifyApiError::Generic(format!("Token exchange failed: {e}"))
            })?;

        let now = Instant::now();
        let expires_in = if token_response.expires_at > now {
            token_response.expires_at.duration_since(now).as_secs()
        } else {
            0
        };

        let new_token = WebApiToken::new(
            token_response.access_token,
            token_response.refresh_token,
            expires_in,
            token_response.scopes.join(" "),
        );

        self.store_token(new_token.clone()).await;

        drop(oauth_state_guard);
        let mut oauth_state_guard = self.oauth_state.write().await;
        *oauth_state_guard = None;

        debug!("oauth flow completed");

        match self.save_token(&new_token).await {
            Ok(_) => debug!("oauth token saved to account.json"),
            Err(e) => {
                error!("oauth token save failed: {e}");
            }
        };

        Ok(new_token)
    }

    /// Adopts a token obtained by another OAuth flow (the librespot login) as the
    /// Web API token, so a single sign-in covers both playback and the account.
    pub async fn adopt_token(&self, token: &OAuthToken) -> Result<(), SpotifyApiError> {
        let now = Instant::now();
        let expires_in = if token.expires_at > now {
            token.expires_at.duration_since(now).as_secs()
        } else {
            0
        };

        let new_token = WebApiToken::new(
            token.access_token.clone(),
            token.refresh_token.clone(),
            expires_in,
            token.scopes.join(" "),
        );

        self.store_token(new_token.clone()).await;

        let mut oauth_state_guard = self.oauth_state.write().await;
        *oauth_state_guard = None;
        drop(oauth_state_guard);

        self.save_token(&new_token).await
    }

    /// Makes `token` the in-memory token and clears the refresh negative cache.
    async fn store_token(&self, token: WebApiToken) {
        let generation = self.token_generation.load(Ordering::Acquire);
        let mut cache = self.token.lock().await;
        cache.replace(token, generation);
    }

    pub async fn save_token(&self, token: &WebApiToken) -> Result<(), SpotifyApiError> {
        let mut path = crate::FILES_DIR
            .get()
            .ok_or_else(|| SpotifyApiError::Generic("Android file path is not set!".to_string()))?
            .clone();

        path.push("account.json");

        let mut file = OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o600)
            .open(&path)?;

        let json = serde_json::to_string(token).map_err(|e| {
            SpotifyApiError::Generic(format!("Failed to serialize WebApiToken: {e}"))
        })?;

        std::io::Write::write_all(&mut file, json.as_bytes())?;
        Ok(())
    }

    pub fn remove_token(&self) -> Result<(), SpotifyApiError> {
        let mut path = crate::FILES_DIR
            .get()
            .ok_or_else(|| SpotifyApiError::Generic("Android file path is not set!".to_string()))?
            .clone();

        path.push("account.json");

        // Drop the memory copy even when the file was already gone.
        self.invalidate_token_cache();

        match std::fs::remove_file(path) {
            Ok(_) => Ok(()),
            Err(e) => {
                error!("account.json removal failed: {e}");
                Err(SpotifyApiError::IO(e))
            }
        }
    }

    /// Whether an account token is stored. Deliberately offline: "is the account connected?"
    /// must not depend on a token refresh succeeding right now (no network, a 429 window),
    /// which used to flip the UI to "connect with Spotify" every time the hourly token
    /// expired while the network was unavailable. A revoked refresh token surfaces later as
    /// an authentication error on the first real API call.
    pub async fn is_oauth_authenticated(&self) -> bool {
        matches!(self.read_stored_token(), Ok(Some(_)))
    }

    pub async fn get_scope(&self) -> Option<String> {
        match self.read_stored_token() {
            Ok(Some(t)) => Some(t.scope),
            _ => None,
        }
    }

    /// Reads the stored token without refreshing it.
    fn read_stored_token(&self) -> Result<Option<WebApiToken>, SpotifyApiError> {
        let mut path = crate::FILES_DIR
            .get()
            .ok_or_else(|| SpotifyApiError::Generic("Android file path is not set!".to_string()))?
            .clone();

        path.push("account.json");

        match std::fs::read_to_string(&path) {
            Ok(contents) => serde_json::from_str::<WebApiToken>(&contents)
                .map(Some)
                .map_err(|e| SpotifyApiError::Generic(format!("Failed to parse token JSON: {e}"))),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(SpotifyApiError::IO(e)),
        }
    }

    /// Returns a usable token: the in-memory copy, account.json when memory is empty, or a
    /// fresh one when the stored token is (about to be) expired. Concurrent callers queue
    /// on the cache mutex, so an expired token is refreshed once and the rest reuse it.
    pub async fn load_token(&self) -> Result<Option<WebApiToken>, SpotifyApiError> {
        let mut cache = self.token.lock().await;
        let token = match self.cached_or_stored_token(&mut cache)? {
            Some(t) => t,
            None => return Ok(None),
        };

        if token.is_expired() {
            let refreshed = self.refresh_locked(&mut cache, &token).await?;
            return Ok(Some(refreshed));
        }

        Ok(Some(token))
    }

    /// Refreshes `token` unless another caller already replaced it while this one was
    /// waiting for the lock (a 401 retry after a concurrent refresh must not POST again).
    pub(crate) async fn refresh_token(&self, token: &WebApiToken) -> Result<WebApiToken, SpotifyApiError> {
        let mut cache = self.token.lock().await;
        if let Some(current) = self.cached_or_stored_token(&mut cache)? {
            if current.access_token != token.access_token && !current.is_expired() {
                debug!("refresh_token: reusing the token refreshed by another caller");
                return Ok(current);
            }
        }
        self.refresh_locked(&mut cache, token).await
    }

    /// The in-memory token, filled from account.json when empty. Caller holds the lock.
    fn cached_or_stored_token(
        &self,
        cache: &mut TokenCache,
    ) -> Result<Option<WebApiToken>, SpotifyApiError> {
        cache.sync_generation(self.token_generation.load(Ordering::Acquire));
        if cache.token.is_none() {
            cache.token = self.read_stored_token()?;
        }
        Ok(cache.token.clone())
    }

    /// POSTs the refresh with the cache lock held. A non-network failure (HTTP 400/401)
    /// is remembered for [`REFRESH_FAILURE_TTL`] and replayed without a request; a 429 is
    /// recorded into the shared rate-limit window by `ensure_success`; network errors are
    /// neither cached nor recorded.
    async fn refresh_locked(
        &self,
        cache: &mut TokenCache,
        token: &WebApiToken,
    ) -> Result<WebApiToken, SpotifyApiError> {
        if let Some((until, message)) = &cache.refresh_failure {
            let now = Instant::now();
            if *until > now {
                debug!(
                    "refresh_token skipped: last failure replayed for another {} s",
                    (*until - now).as_secs()
                );
                return Err(SpotifyApiError::Generic(message.clone()));
            }
            cache.refresh_failure = None;
        }

        let mut form = HashMap::new();
        form.insert("grant_type", "refresh_token");
        form.insert("refresh_token", &token.refresh_token);
        let client_id = self.client_id.lock().unwrap().clone();
        form.insert("client_id", &client_id);

        let response = self
            .client
            .post("https://accounts.spotify.com/api/token")
            .form(&form)
            .send()
            .await?;

        let response = match check_response_json::<TokenResponse>("refresh_token", response).await
        {
            Ok(r) => r,
            Err(e @ SpotifyApiError::Generic(_)) | Err(e @ SpotifyApiError::Http(..)) => {
                let message = e.to_string();
                if message.contains("invalid_client") || message.contains("invalid_grant") {
                    // The refresh token belongs to another client id (credentials changed
                    // after login) or was revoked: no retry will ever succeed. Dropping the
                    // stored account flips the UI to "connect with Spotify", the only way out.
                    warn!("refresh_token rejected, dropping the stored account token: {message}");
                    cache.token = None;
                    cache.refresh_failure = None;
                    if let Err(remove_err) = self.remove_token() {
                        error!("rejected token could not be removed: {remove_err}");
                    }
                    return Err(SpotifyApiError::Generic(format!(
                        "account token rejected, sign in again: {message}"
                    )));
                }
                warn!("refresh_token failed, not retrying for {} s: {message}", REFRESH_FAILURE_TTL.as_secs());
                cache.refresh_failure = Some((Instant::now() + REFRESH_FAILURE_TTL, message));
                return Err(e);
            }
            Err(e) => return Err(e),
        };

        let new_token = WebApiToken::from(response, Some(&token.refresh_token));
        cache.replace(new_token.clone(), self.token_generation.load(Ordering::Acquire));
        self.save_token(&new_token).await?;
        Ok(new_token)
    }
}
