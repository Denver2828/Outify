# Manual test checklist — Spoty 1.7.3

Device checks for the audit remediation shipped in 1.7.3 (commits 164ff0e, 441fae7, 61372ee, 4a21add, 0f0ab13, d62cf6e, 4be2343). Every item here is **not** covered by the unit tests; it needs a phone (and, where noted, a head unit or Bluetooth output). Tick each box only after the expected result was observed.

Legend: **Pre** precondition · **Steps** · **Expect** expected result · **Ref** block / commit.

## 1. Android Auto and landscape mode

- [ ] **Fullscreen lyrics in landscape**
  Pre: landscape layout setting on its default (fullscreen lyrics). Steps: play a track with lyrics, rotate. Expect: lyrics sheet spans the full width, back arrow and heart visible in the header, active line follows playback. Ref: 1.5.x / 1.7.0 (regression check).
- [ ] **Heart on the lyrics screens**
  Steps: in portrait and landscape tap the heart, then open the same track's detail and the player. Expect: all three show the same liked state; tapping again reverts everywhere. Ref: Block A · 164ff0e.
- [ ] **Repeat from the head unit vs in-app**
  Pre: phone connected to Android Auto (or a Bluetooth controller exposing repeat/shuffle). Steps: cycle repeat from the head unit; watch the in-app player button and the notification button. Then cycle from the app. Expect: the three surfaces show the same mode at every step; repeat ONE actually repeats the track; shuffle from the head unit toggles and the notification icon follows. Ref: Block D · 4a21add.
- [ ] **Head unit commands while the session is down**
  Pre: airplane mode. Steps: press repeat/shuffle on the head unit. Expect: the control settles within ~5–10 s, no hang, the reported mode stays at the last confirmed value. Ref: Block D · 4a21add.
- [ ] **Like from the notification / Auto under slow network**
  Pre: throttle the network (or airplane mode). Steps: tap the like button in the notification or on the head unit. Expect: the icon flips immediately (optimistic), and if the remote fails it flips back; no "done" is reported before the operation completes; the app never freezes. Ref: Block A · 164ff0e.

## 2. Connection loss and recovery

- [ ] **Session drop and auto-restart**
  Steps: while playing, toggle airplane mode on for 20 s, then off. Expect: playback resumes or can be resumed without restarting the app; the diagnostics report logs the shutdown and restart. Ref: pre-existing behaviour, regression check after Blocks A/D.
- [ ] **429 countdown in Accounts**
  Pre: provoke a rate limit (repeated logins/syncs) or wait for one. Steps: open Settings → Accounts. Expect: "Spotify limitó los pedidos. Reintentá en N s." counts down; connect button disabled until 0; the account is not shown as disconnected because of the 429. Ref: 1.7.2 · 6058d40, ticker unified in 4be2343.
- [ ] **Home keeps cached content on failure**
  Pre: Home loaded once. Steps: airplane mode, reopen Home. Expect: cached top artists/tracks stay visible with a notice and Retry; the connect card does not replace them. Retry works once online. Ref: Block G · 4be2343.
- [ ] **Liked list during a 429 / network failure**
  Steps: open Liked while rate limited or offline. Expect: local list stays, a notice with countdown or network text appears, Retry is enabled when the countdown reaches 0. Ref: Block G · 4be2343.
- [ ] **Playlist cache-first**
  Pre: open a playlist, then wait less than 15 min. Steps: reopen it with logcat filtered by `fetchMetadata`. Expect: content appears instantly with no network call for that uri; pull-to-refresh does hit the network. Then airplane mode + open a stored playlist: content plus the network notice, Retry works when back online. Ref: Block G · 4be2343.

## 3. Fast successive searches

- [ ] **A then B, A finishes last**
  Pre: slow network helps. Steps: type "a", immediately type "b" (distinct results). Expect: only "b" results are shown; "a" results never flash in afterwards. Ref: Block B · 441fae7.
- [ ] **Clear mid-flight**
  Steps: type a query, clear the field before results arrive. Expect: the screen returns to idle and stays idle when the late response lands. Ref: Block B · 441fae7.
- [ ] **Empty vs error**
  Steps: search a nonsense string online; then search offline. Expect: online → "Nothing found"; offline → network error with Retry (not "Nothing found"). A rate limit shows the countdown and disables Retry until 0. Ref: Block B · 441fae7.
- [ ] **Partial failure**
  Steps: search while one section fails (e.g. flaky network). Expect: loaded sections stay, a partial-failure notice with Retry appears above the list. Ref: Block B · 441fae7.

## 4. Queue

- [ ] **Play next keeps the current track**
  Pre: play a playlist; note track A and its position; confirm "previous" goes back. Steps: long-press track B → "Play next". Expect: A keeps playing at the same position; B is first in the queue sheet; the "inserted" notice appears; "previous" still works (history intact). Ref: Block E · 0f0ab13.
- [ ] **Skip order after play next**
  Steps: skip → B plays; skip again. Expect: the playlist continues after A's original position. Ref: Block E · 0f0ab13.
- [ ] **Play next with queued tracks at the front (known limitation)**
  Steps: "Add to queue" C, then "Play next" D. Expect: D first, C after, A unchanged; the notice reports that history was cleared (this sub-case needs a librespot submodule change and is documented as blocked). Ref: Block E · 0f0ab13.
- [ ] **Duplicates**
  Steps: "Play next" the same track twice. Expect: it appears twice. Ref: Block E · 0f0ab13.
- [ ] **Nothing playing**
  Steps: with nothing playing, "Play next". Expect: "No hay nada reproduciéndose"; no playback starts. Ref: Block E · 0f0ab13.
- [ ] **Failed insert**
  Steps: airplane mode, "Play next". Expect: "No se pudo insertar en la cola"; no success notice. Ref: Block E · 0f0ab13.
- [ ] **Add to queue (end)**
  Steps: "Add to queue" while playing. Expect: the track lands at the end of the queued items, the current track is untouched; offline → "No se pudo agregar a la cola". Ref: Block E · 0f0ab13.

## 5. Lyrics settings

- [ ] **Fallback toggle after a NotFound**
  Pre: Settings → Playback → "Buscar letras en LRCLIB" OFF. Steps: open lyrics for a track Spotify has no lyrics for (shows "not found"); enable the toggle; reopen the same track. Expect: a new lookup runs and, if LRCLIB has it, the lyrics appear with the "Letra provista por LRCLIB" badge. Ref: Block C · 61372ee.
- [ ] **Retry on transient error**
  Steps: airplane mode, open lyrics for an uncached track. Expect: the error message with Retry (not "not found"); back online, Retry loads the lyrics. Ref: Block C · 61372ee.
- [ ] **Concurrent open**
  Steps: open the lyrics sheet and the player card for the same track quickly. Expect: one lookup in logcat, both surfaces show the same result. Ref: Block C · 61372ee.

## 6. Audio recovery

- [ ] **Bluetooth / car output loss mid-track**
  Pre: playing over Bluetooth or the car. Steps: switch the output device off mid-track, then on again. Expect: audio resumes on the fallback route or the reconnected device; the diagnostics report shows `recovery: rebuilds=1..3`. Ref: Block F · d62cf6e.
- [ ] **Rebuild limit and cooldown**
  Steps: cause three output deaths within 30 s (toggle the output repeatedly). Expect: the "could not open the audio output" toast appears once; after the 30 s cooldown with the route back, playback recovers on its own. Ref: Block F · d62cf6e.
- [ ] **No glitch on stop/skip**
  Steps: stop and skip repeatedly during playback. Expect: no replayed fragment from a retained remainder. Ref: Block F · d62cf6e.
- [ ] **Car silence (unconfirmed cause)**
  Steps: reproduce the earlier car-silence scenario and share the diagnostics report. Expect: the `recovery:` counters and the logcat tail explain what happened; do **not** assume 1.7.3 fixed it until this is observed. Ref: Block F · d62cf6e.

## 7. Diagnostics after an ANR

- [ ] **Process exits section**
  Steps: after any "Spoty no responde" dialog, reopen the app, generate the diagnostics report. Expect: a `--- Process exits (last 10) ---` entry with `reason=REASON_ANR` and the main-thread trace. Ref: 1.7.1 · 8b7c30b.
- [ ] **No ANR on like / radio / profile**
  Steps: with a throttled network, like from the player, start a radio, open Settings → Accounts. Expect: the UI stays responsive; no "Skipped N frames" bursts tied to `get_current_user`, `saveItems` or `getRadioForTrack` on the main thread in logcat. Ref: 1.7.2 · 6058d40 and Block A · 164ff0e.
