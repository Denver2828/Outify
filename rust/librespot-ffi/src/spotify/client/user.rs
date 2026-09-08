use reqwest::StatusCode;

use crate::{
    spotify::{
        error::SpotifyApiError,
        search::extract_all_uris,
    },
    types::{
        responses::{ArtistsOrTracksPage, CurrentUserResponse},
    },
};

use super::{check_rate_limit, check_response_json, ensure_success, SpotifyClient, REQUEST_TIMEOUT, SPOTIFY_API_URL};

impl SpotifyClient {
    pub async fn search(
        &self,
        query: &str,
        types: &str,
        limit: Option<i32>,
        offset: Option<i32>,
    ) -> Result<Vec<String>, SpotifyApiError> {
        check_rate_limit("search")?;
        let token = self.load_token().await?;
        let token = token.ok_or_else(|| {
            SpotifyApiError::Generic("No account token present!".to_string())
        })?;

        let mut params = vec![("q", query.to_string()), ("type", types.to_string())];

        if let Some(l) = limit {
            params.push(("limit", l.to_string()));
        }

        if let Some(o) = offset {
            params.push(("offset", o.to_string()));
        }

        let res = self
            .client
            .get(format!("{}/v1/search", SPOTIFY_API_URL))
            .query(&params)
            .bearer_auth(token.access_token)
            .timeout(REQUEST_TIMEOUT)
            .send()
            .await?;

        let res = ensure_success("search", res).await?;

        let text = res.text().await?;

        let parsed: crate::spotify::search::SearchResponse = serde_json::from_str(&text)?;

        let uris = extract_all_uris(parsed);

        Ok(uris)
    }

    pub async fn get_current_user(&self) -> Result<CurrentUserResponse, SpotifyApiError> {
        self.get_current_user_with(false).await
    }

    /// `bypass_rate_limit` is reserved for the diagnostics probe, which must reach Spotify
    /// even while the shared rate-limit window is open.
    pub async fn get_current_user_with(
        &self,
        bypass_rate_limit: bool,
    ) -> Result<CurrentUserResponse, SpotifyApiError> {
        if !bypass_rate_limit {
            check_rate_limit("get_current_user")?;
        }
        let token = self.load_token().await?;
        let token = token.ok_or_else(|| {
            SpotifyApiError::Generic("No account token present!".to_string())
        })?;

        let res = self
            .client
            .get(format!("{}/v1/me", SPOTIFY_API_URL))
            .bearer_auth(token.access_token)
            .timeout(REQUEST_TIMEOUT)
            .send()
            .await?;

        let res = ensure_success("get_current_user", res).await?;

        let text = res.text().await?;

        let data: CurrentUserResponse = serde_json::from_str(&text)?;

        Ok(data)
    }

    pub async fn get_top(
        &self,
        request_type: Option<String>,
        time_range: String,
    ) -> Result<ArtistsOrTracksPage, SpotifyApiError> {
        self.get_top_with(request_type, time_range, false).await
    }

    /// `bypass_rate_limit` is reserved for the diagnostics probe (see `get_current_user_with`).
    pub async fn get_top_with(
        &self,
        request_type: Option<String>,
        time_range: String,
        bypass_rate_limit: bool,
    ) -> Result<ArtistsOrTracksPage, SpotifyApiError> {
        if !bypass_rate_limit {
            check_rate_limit("get_top")?;
        }
        let token = self.load_token().await?;
        let token = token.ok_or_else(|| {
            SpotifyApiError::Generic("No account token present!".to_string())
        })?;

        let request_type = request_type.unwrap_or_else(|| "artists".to_string());

        let url = format!(
            "{}/v1/me/top/{}?time_range={}",
            SPOTIFY_API_URL, request_type, time_range
        );

        let res = self
            .client
            .get(&url)
            .bearer_auth(&token.access_token)
            .timeout(REQUEST_TIMEOUT)
            .send()
            .await?;

        if res.status() == StatusCode::UNAUTHORIZED {
            let new_token = self.refresh_token(&token).await?;
            let res = self
                .client
                .get(&url)
                .bearer_auth(new_token.access_token)
                .timeout(REQUEST_TIMEOUT)
                .send()
                .await?;
            let data = check_response_json::<ArtistsOrTracksPage>("get_top", res).await?;
            return Ok(data);
        }

        let data = check_response_json::<ArtistsOrTracksPage>("get_top", res).await?;

        Ok(data)
    }
}
