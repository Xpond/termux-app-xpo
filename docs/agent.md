# agent — phone-control agent

`agent "<goal>"` drives any app toward a goal by closing a loop over two pieces
XPort already has: the [read-aloud](ttsoverlay.md) accessibility service (its eyes
and hands) and an **OpenAI-compatible chat API on `127.0.0.1`** (its brain). It is
a **real tool-calling agent**: the model emits native OpenAI `tool_calls`, we
execute each one and reply with a `role:"tool"` result — the **settled**
post-action screen — and the model reasons over that truth until it calls `done`.

```
Observe  walk the active window -> a compact JSON list of actionable elements
Decide   the model picks ONE tool: click / type / scroll / back / home /
         wait / open_app / done
Act      execute it, wait for the screen to SETTLE, return the new screen
         as the tool result; repeat until the model calls done
```

The first version parsed action verbs out of prose, which caused runaway clicking:
the model had no channel to say "stop" or "wait", so it always emitted another
action, and completion had to be guessed in code. Tool-calling fixes all three
gaps structurally: `done(summary)` is the model's own stop (and carries its
explanation), `wait` is its honest "show me again", and its `reasoning` is logged
— failures are no longer opaque.

## The brain is an external API (the key design choice)

The agent talks **HTTP** to whatever serves `<host>:<port>/v1/chat/completions`
— it's just an OpenAI client. That endpoint can be:

- the **on-device** model ([`llmd`](llmd.md) → `llama-server`) on `127.0.0.1`, or
- a **full-size model on a paired PC** (e.g. local **Ollama**), reached two ways:
  - **Tailscale (default now)** — the phone hits the PC's MagicDNS name directly
    over the tailnet: `~/.llmd_host = xpo1.beetal-newton.ts.net`, port `11434`.
    No USB, no wireless debugging — the PC just has to be on the tailnet. The
    link is WireGuard-encrypted end to end.
  - **`adb reverse`** — `adb reverse tcp:<port> tcp:<port>` maps the phone's
    `127.0.0.1:<port>` to the PC's, so the loopback call reaches Ollama. Needs a
    USB/wireless-debug connection; the fallback when Tailscale isn't up.

Why external is the default: on-device 1.5–4B models can't emit reliable
tool-calls (learned in the first attempt). A PC model (Ollama `gemma4:e4b`)
does — verified emitting clean `tool_calls` plus a `reasoning` field. The brain
is a one-line swap: the agent reads `~/.llmd_host`, `~/.llmd_port`,
`~/.llmd_model`, `~/.llmd_token` (the `.llmd_*` files `llmd` writes; `host`
defaults to `127.0.0.1`), so pointing those at any server changes the brain with
no code change. `network_security_config.xml` permits cleartext to `127.0.0.1`
and the PC's tailnet host only — never the open LAN.

## Usage — it's a chat

```sh
agent "open the clock and start the stopwatch"
agent "open xpotrack and tell me what tasks I have today"
agent stop      # abort and forget the conversation
```

1. Enable **Settings > Accessibility > xport** and **Display over other apps**.
2. Have a chat API answering at the configured host:port — `llmd start <model>`
   on-device, or a PC server: set `~/.llmd_host` to the PC's tailnet name (e.g.
   `xpo1.beetal-newton.ts.net`) and `~/.llmd_port` to `11434`, or use
   `adb reverse` with host left at `127.0.0.1`.
3. Run `agent "<goal>"`. The terminal becomes the chat: progress and replies
   stream in, and **anything you type is a message into the same conversation**
   — a follow-up after it answers ("now mark it done"), or a mid-run correction
   ("no, the other one"). `done`/plain-text replies yield the turn, they don't
   end the session. Name the app in the goal — the agent launches it itself
   (`open_app`). Ctrl-C/Ctrl-D ends the chat and the run. The full conversation
   is dumped to `~/.agent_conv` for debugging.

**The chat is also the safety mechanism.** Switching to xport **pauses** a
running agent before its next action (learned the hard way: a runaway once
fought the user for the screen) — typing then resumes it with your correction
injected. The agent is hard-blocked from click/type/scroll while xport is
foreground — the chat is yours. `agent stop` (polled at 1 Hz) kills the run and
drops the conversation.

### The context budget

Chat means an ever-growing conversation against a small window. Measured on real
runs, screens are ~90% of the tokens (~700 each); everything else — goal,
corrections, tool calls — costs ~60 tokens a step. Only the latest screen is
truth, so before every model call all but the last two screens are stubbed out
(`prune()`). A real 71-message session measured ~4k tokens where unpruned it
would be ~20k — past Ollama's default context, which truncates silently. What
survives forever is exactly what should: the user's words and the action
narrative.

## How it works

Three small Java pieces (`app/src/main/java/com/xport/terminal/`) + a shell wrapper:

- **`AgentEyes`** — eyes/hands, unchanged across rewrites. `snapshot()` walks the
  active window into compact JSON: one entry per **actionable** element with a
  1-based index `i`, a role (button/field/scroll), and a label. A label attaches
  only to its nearest clickable/editable node (never a scrollable ancestor);
  blank-label editable fields get a `(text field)` placeholder. Nodes are cached
  so `act()` resolves "click 5" to the node the model saw; `performAction` with a
  real-gesture tap fallback for click (canvas UIs). **No tap fallback for scroll**
  — a failed scroll must fail honestly (the fallback once turned it into a blind
  tap that opened a random app).
- **`AgentService`** — the tool-calling loop, on a worker thread. Holds the
  conversation (`org.json`) in **static fields — Android destroys the idle
  service between chat turns** (verified live: a follow-up minutes after `done`
  hit a fresh instance and found nothing). POSTs with the `tools` schema,
  executes the first tool call, appends the `role:"tool"` result — the screen
  **after it settles** (two consecutive equal snapshots), or an explicit
  "still changing — call wait" when it doesn't (ticking UIs never settle).
  `open_app` launches by visible label via `PackageManager` (works from the
  background because the app holds the overlay permission). A repeated call —
  matched one *and two* steps back, since a real run burned 9 steps on a
  click-A/click-B oscillation — gets a "going in circles" note appended. The
  20-step cap (which yields the turn, not the session) and that nudge are the
  only code-side guards; **the model owns stop**. A message typed mid-run is
  injected right after the current tool result; one typed in the run's dying
  moments is handed off to a fresh turn instead of being swallowed.
- **`AgentControl`** — bridges the wrapper to the service: polls `~/.agent_start`,
  `~/.agent_msg` and `~/.agent_stop` at 1 Hz (not a FileObserver — a second
  observer on app home breaks [`LlmServerControl`](llmd.md)'s).
- **`agent`** (shell) — writes the goal trigger, then becomes the chat: `tail -f`
  on `~/.agent_log` streams output while a `read` loop forwards typed lines to
  `~/.agent_msg`. Two minimal-environment traps cost a debugging session:
  the session tty is left raw by the shell (`-icrnl` — Enter sends `\r`, which
  never ends a line, so `read` hangs forever; fixed with `stty icrnl icanon
  echo`), and **the bootstrap has no `printf`** (not an mksh builtin either —
  the failed redirect left an empty `.agent_msg` that was silently dropped;
  use `echo`).

### Prompting: instructions go in the user message

The defining quirk found while tuning (verified by replaying real device
conversations against Ollama): **gemma ignores instructions in the system prompt
and in tool descriptions, but follows the identical words in the user message.**
So the behavioral rules ride with the goal: "call open_app first", "answer
questions in done's summary". The system prompt keeps a copy for better models.

## Status & limits

Proven end-to-end on-device (PC `gemma4:e4b` over `adb reverse`):

- **Navigation + action** — `open_app` as step 1 (no home-screen hunting),
  multi-screen click/type/scroll, correct `wait` on mid-transition screens.
- **Question goals** — "open xpotrack and tell me what tasks I have today" →
  3 steps → `done: The scheduled task for today is "testing xport".` The agent
  answers from what the screen shows.
- **Safety** — both kill switches verified live; runaway clicking is gone
  (the model stops itself or explains why it can't proceed).
- **Multi-turn chat** — a real session: greeting, "summarize the tokenization
  note" (3 steps, good summary), "what do you think of it?" (answered in place),
  "now create a task for it" (corrected mid-way via pause+message, task
  created). The natural-feedback loop works: the user catches mistakes the
  model can't see.

Standing limits:

- **The model can't always judge success from controls alone.** After starting a
  stopwatch the screen showed Reset/Pause/Lap and gemma still didn't infer
  "running" — its own reasoning said "already active" and it acted anyway. Every
  prompt-side remedy failed; this is a model wall, not a protocol gap. The fix is
  a stronger brain (one-line swap via `~/.llmd_model`), not more tuning.
- **The model sees only actionable elements.** Read-only text (a note's body, a
  message thread) is invisible, so "read X and tell me" works only when the data
  happens to be clickable. Exposing `role:"text"` elements is the next snapshot
  change.
- English labels; changing the a11y gesture capability drops the Accessibility
  grant (re-enable after such an update). Bootstrap scripts (`scripts/agent`)
  don't update on APK reinstall — push via `adb` or re-extract.

## Next steps

1. **Expose text elements** in `snapshot()` (`role:"text"`, not clickable) so the
   agent can read screens, not just act on them — required for most real
   "check / read / summarize" goals.
2. **Try a stronger tool-calling brain** in Ollama for the success-judgment wall
   (e.g. the click-A/click-B oscillation: the model couldn't see it was looping
   even with the nudge pointing at it).
