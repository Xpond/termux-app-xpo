# Read-aloud — speak any app's screen

Point at any app or webpage and have it read to you. A floating ▶ button reads
the current screen aloud through the on-device [`tts`](tts.md) engine (KittenTTS),
playing continuously while you switch to the app you're listening to. No network,
no Share sheet, no other app's cooperation — an accessibility service reads the
screen directly.

## Usage

1. Grant **Settings > Accessibility > xport** (one-time; the OS gate for reading
   the screen) and **Display over other apps** (for the button + background audio).
2. A floating **▶** appears (drag to reposition). Open any app/webpage, tap it →
   it reads the visible body text. Tap **■** to stop. It stops on its own at the
   end and the icon resets.

The button shows only while accessibility is enabled, and hides when you turn it
off. Requires `tts pull` to have fetched the model first.

## Architecture

Three pieces, all in-process (`app/src/main/java/com/xport/terminal/`):

- **`TtsAccessibilityService`** — the only frontend. On a tap it walks the active
  window's node tree and collects the visible **body** text: skips off-screen and
  invisible nodes, skips text inside clickable containers (buttons / nav chrome),
  and emits one string per leaf. Hands it to the player; reads on demand only (no
  event subscription — see Performance).
- **`TtsPlayerService`** — foreground service that owns playback. Splits text into
  sentences and feeds them to a **resident `tts-bin --serve`** process (model
  loads once). Two threads decouple synth from playback: a synth thread fills a
  small bounded PCM queue, a player thread drains it to `AudioTrack` back-to-back,
  so there's no gap between sentences (synth is ~10× real-time). Holds the same
  1×1 overlay as [`llmd`](llmd.md) for background speed. When the speaker goes
  idle it fully tears down (kills `tts-bin`, drops the overlay) so nothing
  lingers.
- **`TtsFloatingButton`** — the draggable ▶ overlay. The service shows/hides it on
  connect/disconnect; tapping toggles read/stop.

No HTTP, no daemon, no token: unlike `llmd`, the consumers are XPort's own
in-process frontends, so a loopback server would be the wrong primitive. "Speak
this" is one-way — the screen reader reads, the player plays.

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
- **No scrolling.** Reads the text the accessibility tree exposes for the current
  screen (often the whole article) and stops. Apps that lazy-load content only on
  scroll won't have their tail read.
- **Heuristic chrome-stripping.** "Clickable ancestor = control" skips nav, but an
  app that marks real content clickable could lose a little text.
