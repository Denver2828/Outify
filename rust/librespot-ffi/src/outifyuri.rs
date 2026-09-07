use librespot_core::SpotifyUri;

use crate::session::get_username;

#[derive(Clone, PartialEq, Eq, Hash)]
pub enum OutifyUri {
    Spotify(SpotifyUri),
    Liked,
    ArtistLiked { id: String },
}

impl OutifyUri {
    pub fn from_uri(uri: &str) -> Self {
        let mut parts = uri.split(':');

        match parts.next() {
            Some("spotify") => match SpotifyUri::from_uri(uri) {
                Ok(spotify_uri) => Self::Spotify(spotify_uri),
                Err(_) => Self::Spotify(SpotifyUri::Unknown {
                    kind: parts.next().unwrap_or("").to_owned().into(),
                    id: parts.next().unwrap_or("").to_owned(),
                }),
            },

            Some("outify") => match (parts.next(), parts.next(), parts.next()) {
                (Some("liked"), Some("artist"), Some(id)) => {
                    Self::ArtistLiked { id: id.to_owned() }
                }
                (Some("liked"), _, _) => Self::Liked,
                (Some(kind), Some(id), _) => Self::Spotify(SpotifyUri::Unknown {
                    kind: kind.to_owned().into(),
                    id: id.to_owned(),
                }),
                _ => Self::Spotify(SpotifyUri::Unknown {
                    kind: "".to_owned().into(),
                    id: "".to_owned(),
                }),
            },

            Some(kind) => Self::Spotify(SpotifyUri::Unknown {
                kind: kind.to_owned().into(),
                id: parts.next().unwrap_or("").to_owned(),
            }),

            None => Self::Spotify(SpotifyUri::Unknown {
                kind: "".to_owned().into(),
                id: "".to_owned(),
            }),
        }
    }

    pub fn to_uri(&self) -> String {
        match &self {
            OutifyUri::Spotify(uri) => uri.to_uri(),
            OutifyUri::Liked => match get_username() {
                Some(user_id) => format!("spotify:user:{}:collection", user_id),
                None => Self::NO_SESSION_URI.to_owned(),
            },
            OutifyUri::ArtistLiked { id } => match get_username() {
                Some(user_id) => format!("spotify:user:{}:collection:artist:{}", user_id, id),
                None => Self::NO_SESSION_URI.to_owned(),
            },
        }
    }

    /// Returned instead of a collection URI while there is no session. It is deliberately not
    /// a valid Spotify URI, so every caller's `SpotifyUri::from_uri` fails cleanly instead of
    /// the process aborting on a panic.
    pub const NO_SESSION_URI: &'static str = "outify:no-session";
}
