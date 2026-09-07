//! Planning for "play next" insertions.
//!
//! librespot exposes two queue primitives and neither is a plain "insert at the
//! front of the next tracks":
//!
//! * `add_to_queue` inserts right after the last *queued* track, which is the
//!   front of the list only while nothing else is queued. It keeps the current
//!   track, its position, the history and every provider flag intact.
//! * `set_queue(tracks, None)` replaces the next tracks wholesale. It keeps the
//!   current track and its position but clears the history and drops the
//!   queue provider flags of the existing next tracks.
//!
//! Passing a `playing_track` to `set_queue` must never be used for an insert:
//! the native handler resolves it as an index into the *previous* context and
//! replaces the current track.
//!
//! This module decides, from the current next tracks, which primitive gives the
//! caller the requested order with the least collateral damage. It is pure so
//! it can be unit tested without a Spirc session.

use librespot_protocol::player::ProvidedTrack;

const PROVIDER_QUEUE: &str = "queue";
const METADATA_IS_QUEUED: &str = "is_queued";

/// How the insertion is going to be performed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InsertNextPlan {
    /// Nothing is queued ahead: `add_to_queue` in order lands the new tracks
    /// at the front while preserving everything else.
    AddToQueue,
    /// Queued tracks already sit at the front: the only way to put the new
    /// tracks before them is to rewrite the next tracks. Carries the full
    /// list to push (new tracks first, then the existing next tracks).
    ReplaceNextTracks(Vec<String>),
}

fn is_queued(track: &ProvidedTrack) -> bool {
    track.provider == PROVIDER_QUEUE
        || track
            .metadata
            .get(METADATA_IS_QUEUED)
            .map(|v| v == "true")
            .unwrap_or(false)
}

/// Decides how to insert `new_uris` in front of `existing_next` so that the
/// first new uri becomes the next track.
///
/// Duplicates are allowed on purpose: Spotify itself lets the same track be
/// queued several times, and de-duplicating here would silently drop a request.
pub fn plan_insert_next(new_uris: &[String], existing_next: &[ProvidedTrack]) -> InsertNextPlan {
    let queued_at_front = existing_next.first().map(is_queued).unwrap_or(false);

    if !queued_at_front {
        return InsertNextPlan::AddToQueue;
    }

    let mut merged: Vec<String> = Vec::with_capacity(new_uris.len() + existing_next.len());
    merged.extend(new_uris.iter().cloned());
    merged.extend(
        existing_next
            .iter()
            .map(|t| t.uri.clone())
            .filter(|uri| !uri.is_empty()),
    );
    InsertNextPlan::ReplaceNextTracks(merged)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn track(uri: &str, provider: &str) -> ProvidedTrack {
        ProvidedTrack {
            uri: uri.to_string(),
            provider: provider.to_string(),
            ..Default::default()
        }
    }

    fn queued_by_metadata(uri: &str) -> ProvidedTrack {
        let mut t = track(uri, "");
        t.metadata
            .insert(METADATA_IS_QUEUED.to_string(), "true".to_string());
        t
    }

    #[test]
    fn empty_next_tracks_uses_add_to_queue() {
        let plan = plan_insert_next(&["spotify:track:b".into()], &[]);
        assert_eq!(plan, InsertNextPlan::AddToQueue);
    }

    #[test]
    fn context_tracks_at_front_use_add_to_queue() {
        let existing = vec![track("spotify:track:c1", "context"), track("spotify:track:c2", "context")];
        let plan = plan_insert_next(&["spotify:track:b".into()], &existing);
        assert_eq!(plan, InsertNextPlan::AddToQueue);
    }

    #[test]
    fn queued_tracks_at_front_replace_next_tracks_keeping_order() {
        let existing = vec![
            track("spotify:track:q1", "queue"),
            track("spotify:track:q2", "queue"),
            track("spotify:track:c1", "context"),
        ];
        let plan = plan_insert_next(&["spotify:track:b".into(), "spotify:track:b2".into()], &existing);
        assert_eq!(
            plan,
            InsertNextPlan::ReplaceNextTracks(vec![
                "spotify:track:b".into(),
                "spotify:track:b2".into(),
                "spotify:track:q1".into(),
                "spotify:track:q2".into(),
                "spotify:track:c1".into(),
            ])
        );
    }

    #[test]
    fn is_queued_metadata_counts_as_queued() {
        let existing = vec![queued_by_metadata("spotify:track:q1")];
        let plan = plan_insert_next(&["spotify:track:b".into()], &existing);
        assert!(matches!(plan, InsertNextPlan::ReplaceNextTracks(_)));
    }

    #[test]
    fn duplicates_are_preserved() {
        let existing = vec![track("spotify:track:b", "queue")];
        let plan = plan_insert_next(&["spotify:track:b".into()], &existing);
        assert_eq!(
            plan,
            InsertNextPlan::ReplaceNextTracks(vec!["spotify:track:b".into(), "spotify:track:b".into()])
        );
    }

    #[test]
    fn empty_uris_in_existing_are_dropped() {
        let existing = vec![track("spotify:track:q1", "queue"), track("", "context")];
        let plan = plan_insert_next(&["spotify:track:b".into()], &existing);
        assert_eq!(
            plan,
            InsertNextPlan::ReplaceNextTracks(vec!["spotify:track:b".into(), "spotify:track:q1".into()])
        );
    }
}
