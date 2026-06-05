# llmd — local LLM HTTP API

`llmd` exposes the on-device LLM ([`llm`](xport.md#on-device-llm-llm) / llama.cpp)
as an **OpenAI-compatible HTTP API on `127.0.0.1`**, kept alive by a foreground
service so other apps on the same phone can use it. Models stay user-supplied in
`~/models` (shared with `llm`); nothing is bundled.

Why loopback: on Android, `127.0.0.1` is shared across every app's sandbox, so an
HTTP server one app opens is reachable by all others with no IPC, permissions, or
protocol code — consumers just use any OpenAI client library.

## Usage

```sh
llmd start [name] [port]   # serve a model (default: ~/models default, port 8080)
llmd stop                  # stop the server
llmd status                # is it running?
llmd token                 # print the API token (bake into your apps)
```

- `llmd start` with no name serves the model `llm run` last set as default.
  `llmd start qwen2.5:3b` or `llmd start qwen2.5:3b 9090` to override.
- XPort must be in the **foreground** when you run `llmd start` (Android only lets
  a foregrounded app start a foreground service).

## Calling the API

```sh
# from another app/process on the device, or your computer via:
#   adb forward tcp:8080 tcp:8080
curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Authorization: Bearer $(cat ~/.llmd_token)" \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"hello"}]}'
```

Standard endpoints: `/v1/chat/completions`, `/v1/completions`, `/v1/models`,
`/health`. A browser **WebUI** is served at `http://127.0.0.1:8080/`.

## Security

Loopback is open to *every* app on the device, so the API is scoped by a bearer
token (`--api-key`): inference endpoints `401` without it. The token is generated
once on first `llmd start`, stored in `~/.llmd_token` (mode 600), and reused.
Bake the same token into each of your apps. The sandbox stops other apps reading
`~/.llmd_token`, so this is sufficient for "my apps only" on one device. The bind
stays on `127.0.0.1` — never `0.0.0.0` (that would expose it to the whole LAN).

## Background speed & the overlay permission

A foreground service keeps the server process *alive* when XPort is backgrounded,
but Android still schedules it in the `/background` cpuset (little cores only),
which throttles inference ~4x. To run at full speed in the background the service
holds a **1×1 transparent overlay window**, which raises the process to a
"visible" state and the `/foreground` cpuset (all cores).

This needs the **"Display over other apps"** permission:

```
Settings > Apps > xport > Display over other apps > Allow
```

Without it the server still works, just throttled when backgrounded; `llmd start`
prints a warning (and reports status `RUNNING_SLOW`). The remaining small gap vs a
foregrounded app (the prime-core bias of the `/top-app` cpuset) is reserved by
Android for the on-screen app and is unreachable without root.

## How it works

Control reuses XPort's file-trigger pattern (no `am`/broadcasts — the minimal
shell can't reach them), the same mechanism as the font commands and `llm`:

1. `llmd start` writes `~/.llmd_start` (model path, port, token) and polls
   `~/.llmd_status`.
2. `LlmServerControl` (a `FileObserver` in `TermuxActivity`) sees it and calls
   `startForegroundService()`.
3. `LlmServerService` posts its notification, adds the overlay, and spawns
   `llama-server -m <model> --host 127.0.0.1 --port <port> --api-key <token>
   -c 2048 -t 4` via `ProcessBuilder`, holding the process handle. It writes
   `RUNNING` (or `RUNNING_SLOW` if the overlay couldn't be added).
4. `llmd stop` writes `~/.llmd_stop`; the service kills the process, drops the
   overlay + notification, and `stopSelf()`s.

The service is a thin supervisor only — it never speaks HTTP or does inference;
`llama-server` does all of that. Target SDK is 28, so no `foregroundServiceType`
/ `specialUse` machinery is needed; plain `FOREGROUND_SERVICE` + a notification.

Files (all under `~` = `/data/data/com.xport.terminal/files/home`):
`.llmd_start`, `.llmd_stop`, `.llmd_status`, `.llmd_token`, `.llmd_port`,
`.llmd_log` (server stdout/stderr).

## What's shipped

`llama-server` is built from the same pinned llama.cpp as `llama-cli` (it was
already compiled — `LLAMA_BUILD_SERVER=ON`), adding only `bin/llama-server` and
`lib/libllama-server-impl.so` to the bootstrap (its other deps are static). The
WebUI assets are fetched prebuilt from HuggingFace at build time. `llmd` is a
plain shell script. See `scripts/build-minimal-bootstrap.sh` (`build_llama`).

## Limits

- **Single-tenant, serial.** One model in RAM, one generation at a time; the API
  is a local endpoint, not a concurrent cloud one.
- **Thermal ceiling.** Sustained load throttles the SoC after ~60–90s. Design for
  short, user-initiated turns.
- **Cold start.** The first request after `llmd start` pays model-load + page-in
  latency; tolerate a slow first call.
- **No reboot survival.** Android restricts background FGS starts from boot; start
  it from XPort when you need it.
