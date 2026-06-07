# Read-aloud — speak any app's screen

Point at any app or webpage and have it read to you, through the on-device
[`tts`](tts.md) engine (KittenTTS), playing continuously while you switch to the
app you're listening to. No network, no other app's cooperation. Three ways in:

- **Tap the floating ▶ button** — an accessibility service reads the current
  screen's body text.
- **Select text → "Speak"** — a `PROCESS_TEXT` menu item reads exactly the
  selection. The reliable path for browsers, whose page selection the screen-walk
  can't see (Chrome renders the page in one virtual view, hidden from the a11y
  tree).
- **Copy text → long-press ▶** — reads the clipboard. The fallback for apps whose
  trimmed selection menu (some OEM skins) won't show "Speak".

## Usage

1. Grant **Settings > Accessibility > xport** (one-time; the OS gate for reading
   the screen) and **Display over other apps** (for the button + background audio).
2. A floating **▶** appears (drag to reposition). Open any app/webpage, tap it →
   it reads the visible body text and the icon becomes **⏸**. **Tap to pause**
   (icon → ▶); tap again to **resume** from the start of the sentence it was on.
   **Double-tap** to stop. It also stops on its own at the end and the icon resets.
3. To read a **selection**: highlight text and pick **Speak** from the popup menu
   (may be under ⋮). If the menu doesn't offer it, **Copy** the text and
   **long-press** the ▶ button instead — same result. Both drive the same icon
   state as a tap.

The button shows only while accessibility is enabled, and hides when you turn it
off. Selection-read works regardless. Requires `tts pull` to have fetched the
model first.

## Architecture

Three pieces, all in-process (`app/src/main/java/com/xport/terminal/`):

- **`TtsAccessibilityService`** — the only frontend. On a tap it walks the active
  window's node tree and collects the visible **body** text: skips off-screen and
  invisible nodes, skips text inside clickable containers (buttons / nav chrome),
  and emits one string per leaf. Hands it to the player; reads on demand only (no
  event subscription — see Performance).
- **`TtsPlayerService`** — foreground service that owns playback, and the single
  entry point: every frontend calls its static `speak(ctx, text)`. Splits text
  into sentences and feeds them to a **resident `tts-bin --serve`** process (model
  loads once). Two threads decouple synth from playback: a synth thread fills a
  small bounded PCM queue, a player thread drains it to `AudioTrack` back-to-back,
  so there's no gap between sentences (synth is ~10× real-time). Holds the same
  1×1 overlay as [`llmd`](llmd.md) for background speed. When the speaker goes
  idle it fully tears down (kills `tts-bin`, drops the overlay) so nothing
  lingers. A `mPending` counter keeps it from mistaking the ~1s model-load gap
  (queues briefly empty before the first PCM) for "done" and tearing down early.
  **Pause/resume** is non-destructive: pause stops the speaker and keeps the synth
  loaded. Each synth+player thread pair carries a **generation** token, so
  pause/resume just bump it to retire the old pair without joining (both are
  instant). The player caches the sentence it's voicing; resume replays that PCM
  from memory and re-synths only from the *next* sentence — so resume is instant
  even though one synth takes ~seconds. A lock around the pipe lets a retired
  worker finish its in-flight read before the new one writes, keeping the
  length-prefixed protocol aligned.
- **`TtsFloatingButton`** — the draggable ▶ overlay. The service shows/hides it on
  connect/disconnect; **single tap** = read / pause / resume (whichever the state
  calls for), **double tap** = stop, **long-press** reads the clipboard (via
  `TtsReadClipboard`). The single tap fires immediately; a second tap inside the
  double-tap window issues stop on top of it (no per-tap latency). A round glassy
  bubble; the glyph (⏸ playing, ▶ idle/paused) says what the next tap will do.
- **`TtsProcessText`** — the "Speak" menu item (`PROCESS_TEXT`). Invisible
  (`Theme.NoDisplay`); the system hands it the selected text, it calls `speak()`
  and finishes. Works in browsers, where the selection never reaches the a11y tree.
- **`TtsReadClipboard`** — reads the clipboard (long-press fallback). Android 10+
  blocks clipboard reads without window focus, so this is a real **transparent**
  activity (not `Theme.NoDisplay`, which crashes on `finish()` after focus): it
  reads in `onWindowFocusChanged`, calls `speak()`, finishes. Own `taskAffinity`
  so it never surfaces XPort's main UI.

No HTTP, no daemon, no token: unlike `llmd`, the consumers are XPort's own
in-process frontends, so a loopback server would be the wrong primitive. "Speak
this" is one-way — a frontend reads text, the player plays.

## `tts-bin --serve`

The same `tts-bin` ([scripts/tts-src/tts.c](../scripts/tts-src/tts.c)) gained a
serve mode beside its one-shot WAV mode. It loads espeak + the voice + the ONNX
session **once**, then loops: read one sentence per line on stdin, write the PCM
back on stdout as `<uint32 LE byte-count><16-bit mono 24 kHz PCM>` (count 0 =
synth failure). This is what removes the per-sentence model-reload gap; the
one-shot `tts "text" out.wav` path is unchanged.

## Performance notes

- **No event subscription.** The accessibility config sets
  `accessibilityEventTypes=""` — we read on demand via `getRootInActiveWindow()`,
  never on UI events. Subscribing made Android recompute the a11y tree (including
  XPort's heavy terminal view) on every change, lagging keystrokes ~2–3 s.
- **Terminal excluded from a11y.** `TerminalView` is set
  `IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS` — we never read XPort's own
  terminal, so it stays out of the tree.
- **Button is `NOT_TOUCH_MODAL`.** Without that flag a touchable overlay grabs
  whole-screen touches and swallows taps outside it (e.g. the keyboard).

## Limits

- **English only**, inherits from `tts` / KittenTTS.
- The next two limits are the **screen-walk's** (tap). Selecting text and using
  **Speak** / clipboard long-press reads exactly what you picked, so they're the
  escape hatch when the walk misses.
- **No scrolling.** Reads the text the accessibility tree exposes for the current
  screen (often the whole article) and stops. Apps that lazy-load content only on
  scroll won't have their tail read.
- **Heuristic chrome-stripping.** "Clickable ancestor = control" skips nav, but an
  app that marks real content clickable could lose a little text.
