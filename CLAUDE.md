# XPort

Minimal SSH-focused Android terminal forked from Termux. Package
`com.xport.terminal`, ARM64-only, ~13 MB bootstrap (no APT/compilers/editors).
Full architecture: `docs/xport.md`.

- **Modules:** `app`, `terminal-emulator`, `terminal-view`, `termux-shared`.
- **Build:** needs NDK `22.1.7171670` + `platforms;android-34`; set
  `local.properties` `sdk.dir`. Output is the **debug** arm64 APK, signed with
  `app/testkey_untrusted.jks`. Build order is circular: `assembleDebug` →
  re-run `scripts/build-minimal-bootstrap.sh` → `assembleDebug` again.
- **Bootstrap:** not in git; compiled from source (toybox, dropbear, openssl)
  into `assets/xport-bootstrap-arm64-v8a.zip`.
- **Custom commands** (`app/src/main/cpp/`): `fontsize`, `font`, `textcolor`,
  `backgroundcolor` use the **file-trigger pattern** (C writes `~/.X` +
  `~/.X_changed`; `TermuxActivity` polls and applies). No `am`/broadcasts — the
  minimal shell can't reach framework tools.
- **On-device LLM** (`llm`): runs GGUF models via a pinned `llama.cpp` (CPU-only,
  built with an isolated r27c NDK). Models user-supplied in `~/models`.
- **Local LLM API** (`llmd`): serves a model as an OpenAI-compatible HTTP API on
  `127.0.0.1` via `llama-server`, kept alive by a foreground service (file-trigger
  start/stop). Token-gated; holds an invisible overlay for full background speed.
  Details: `docs/llmd.md`.
- **On-device TTS** (`tts`): text→WAV via KittenTTS (mini-0.8, ONNX Runtime) +
  espeak-ng for phonemes. Native `tts-bin` (`scripts/tts-src/`), English only,
  model user-supplied in `~/models/tts` (`tts pull`). Engine built in `build_tts`
  with the same r27c NDK as llama. Details: `docs/tts.md`.
- **Read-aloud** (speak any app's screen): floating ▶ button → an accessibility
  service reads the current screen's body text → `TtsPlayerService` (foreground)
  plays it via `AudioTrack`, feeding sentences to a resident `tts-bin --serve`
  (model loaded once, gap-free). Two more entry points for selected text (which
  the screen-walk can't get from browsers): a **"Speak" `PROCESS_TEXT`** menu item
  (`TtsProcessText`) and **clipboard long-press** (`TtsReadClipboard`, a focus-
  grabbing transparent activity — Android 10+ blocks background clipboard reads).
  All call `TtsPlayerService.speak()`. In-process, no HTTP/daemon (unlike `llmd` —
  "speak this" is one-way). Java in `app/.../com/xport/terminal/Tts*.java`.
  Needs Accessibility + overlay permissions. Details: `docs/ttsoverlay.md`.
- **Phone-control agent** (`agent "<goal>"`): native **tool-calling** loop. The
  a11y service (read-aloud's `snapshot()`/`act()` in `AgentEyes`) is the eyes/
  hands; an **OpenAI-compatible chat API on `127.0.0.1`** is the brain — on-device
  `llmd`, or a full model on a PC via `adb reverse` (the default). The model calls
  tools (`click`/`type`/`scroll`/`back`/`home`/`wait`/`open_app`/`done`); each
  result is the settled post-action screen; **the model owns completion**
  (`done(summary)` — for question goals the summary is the answer). Behavioral
  instructions must ride in the **user message** (gemma ignores system prompt).
  **It's a chat**: the terminal streams replies and typed lines feed the same
  conversation (`.agent_msg`); `done`/plain text yield the turn, not the session
  (conversation is static — Android kills the idle service between turns).
  Context stays flat: all but the last 2 screens are pruned before each call.
  Switching to xport *pauses* the run (typing resumes); `agent stop` ends it.
  `AgentControl` polls `.agent_start`/`.agent_msg`/`.agent_stop`. Wrapper traps:
  tty is raw (`stty icrnl` needed) and bootstrap has no `printf`. Needs
  Accessibility + overlay. Details: `docs/agent.md`.

---
