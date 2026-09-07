//! Planning for "play next" insertions.
//!
//! librespot (our fork) exposes two queue primitives that keep the current
//! track, its position, the history and every provider flag intact:
//!
//! * `add_to_queue` inserts right after the last *queued* track, which is the
//!   front of the list only while nothing else is queued.
//! * `play_next` inserts at index 0, ahead of every queued track. Each call
//!   pushes the previous front one slot down, so a batch has to be sent in
//!   reverse to end up in the requested order.
//!
//! `set_queue` is never used for an insert: with a `playing_track` the native
//! handler resolves it as an index into the *previous* context and replaces
//! the current track, and even with `None` it clears the history.
//!
//! This module decides, from the current next tracks, which primitive to use.
//! It is pure so it can be unit tested without a Spirc session.

use librespot_protocol::player::ProvidedTrack;

const PROVIDER_QUEUE: &str = "queue";
const METADATA_IS_QUEUED: &str = "is_queued";

/// How the insertion is going to be performed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InsertNextPlan {
    /// Nothing is queued ahead: `add_to_queue` in order lands the new tracks
    /// at the front while preserving everything else.
    AddToQueue,
    /// Queued tracks already sit at the front: `play_next` per uri, in the
    /// carried order (already reversed, so the first requested uri ends up
    /// first).
    PlayNext(Vec<String>),
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

    let mut reversed: Vec<String> = new_uris.to_vec();
    reversed.reverse();
    InsertNextPlan::PlayNext(reversed)
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
    fn queued_tracks_at_front_use_play_next_in_reverse_order() {
        let existing = vec![
            track("spotify:track:q1", "queue"),
            track("spotify:track:q2", "queue"),
            track("spotify:track:c1", "context"),
        ];
        let plan = plan_insert_next(&["spotify:track:b".into(), "spotify:track:b2".into()], &existing);
        // play_next(b2) then play_next(b) leaves b first, b2 second, then q1, q2, c1.
        assert_eq!(
            plan,
            InsertNextPlan::PlayNext(vec!["spotify:track:b2".into(), "spotify:track:b".into()])
        );
    }

    #[test]
    fn is_queued_metadata_counts_as_queued() {
        let existing = vec![queued_by_metadata("spotify:track:q1")];
        let plan = plan_insert_next(&["spotify:track:b".into()], &existing);
        assert!(matches!(plan, InsertNextPlan::PlayNext(_)));
    }

    #[test]
    fn duplicates_are_preserved() {
        let existing = vec![track("spotify:track:b", "queue")];
        let plan = plan_insert_next(&["spotify:track:b".into()], &existing);
        assert_eq!(plan, InsertNextPlan::PlayNext(vec!["spotify:track:b".into()]));
    }

    #[test]
    fn single_uri_is_not_reordered() {
        let existing = vec![track("spotify:track:q1", "queue"), track("", "context")];
        let plan = plan_insert_next(&["spotify:track:b".into()], &existing);
        assert_eq!(plan, InsertNextPlan::PlayNext(vec!["spotify:track:b".into()]));
    }
}
