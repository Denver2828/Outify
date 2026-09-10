use reqwest::StatusCode;

use crate::{
    spotify::error::SpotifyApiError,
    types::{
        requests::TransferPlaybackRequest,
        responses::DevicesResponse,
    },
};

use super::GatedRequest;
use super::{check_rate_limit, check_response_json, ensure_success, SpotifyClient, REQUEST_TIMEOUT, SPOTIFY_API_URL};

impl SpotifyClient {
    pub async fn get_devices(&self) -> Result<DevicesResponse, SpotifyApiError> {
        check_rate_limit("get_devices")?;
        let token = self.load_token().await?;
        let token = token.ok_or_else(|| {
            SpotifyApiError::Generic("No account token present!".to_string())
        })?;

        let url = format!("{}/v1/me/player/devices", SPOTIFY_API_URL);

        let res = self
            .client
            .get(&url)
            .bearer_auth(&token.access_token)
            .timeout(REQUEST_TIMEOUT)
            .send_gated("get_devices", false)
            .await?;

        if res.status() == StatusCode::UNAUTHORIZED {
            let new_token = self.refresh_token(&token).await?;
            let res = self
                .client
                .get(&url)
                .bearer_auth(new_token.access_token)
                .timeout(REQUEST_TIMEOUT)
                .send_gated("get_devices", false)
                .await?;
            let data = check_response_json::<DevicesResponse>("get_devices", res).await?;
            return Ok(data);
        }

        let data = check_response_json::<DevicesResponse>("get_devices", res).await?;

        Ok(data)
    }

    pub async fn transfer_playback(
        &self,
        device_id: String,
    ) -> Result<StatusCode, SpotifyApiError> {
        check_rate_limit("transfer_playback")?;
        let token = self.load_token().await?;
        let token = token.ok_or_else(|| {
            SpotifyApiError::Generic("No account token present!".to_string())
        })?;

        let body = TransferPlaybackRequest {
            device_ids: vec![device_id],
        };

        let res = self
            .client
            .put(format!("{}/v1/me/player", SPOTIFY_API_URL))
            .bearer_auth(token.access_token)
            .timeout(REQUEST_TIMEOUT)
            .json(&body)
            .send_gated("transfer_playback", false)
            .await?;

        let res = ensure_success("transfer_playback", res).await?;

        Ok(res.status())
    }
}
