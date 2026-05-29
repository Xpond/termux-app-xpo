# XPort

A minimal, SSH-focused Android terminal forked from Termux. Package
`com.xport.terminal`, label **xport**, v1.0.0. ARM64-only. ~13 MB bootstrap vs
Termux's ~180 MB.

## What it is

A stripped Termux: the APT package manager, compilers, languages, and editors
are gone. What's left is a terminal emulator + a tiny bootstrap of statically
useful binaries, plus a handful of custom commands wired into the Java app.

## Modules (Gradle)

- `app` — the Android app (`com.termux.app.*`, `com.xport.terminal.*`).
- `terminal-emulator` — VT/escape parsing, terminal state.
- `terminal-view` — the rendering `View`, fonts, color themes.
- `termux-shared` — shared utilities, preferences, shell plumbing.

## Build

Requires **NDK 22.1.7171670** (r22b, clang 11.0.5) and `platforms;android-34`;
`local.properties` → `sdk.dir`. Builds on Gradle 8.5 + JDK 21 (AGP 8.0.2).
Output: `app/build/outputs/apk/debug/termux-app_xport-debug_arm64-v8a.apk`.
Signed with `app/testkey_untrusted.jks` (the shipped app uses this exact cert).

The shipped APK is the **debug** variant (dex is R8-shrunk: names kept, unused
code stripped).

### Bootstrap (`scripts/build-minimal-bootstrap.sh`)

Not in git (gitignored). Compiles from source for arm64-v8a and zips into
`assets/xport-bootstrap-arm64-v8a.zip` (~14 MB), copied into assets by
`scripts/gradle-bootstrap-integration.gradle` at `preBuild`:

- **toybox 0.8.10** — core utils (SELinux/netcat disabled for NDK).
- **dropbear 2025.88** (client-only) — `ssh`/`dbclient`, `scp`, `dropbearkey`,
  `dropbearconvert`. ARM64 TLS-alignment fix applied by binary-patching the
  PT_TLS program header to 64-byte align (`.bak` copies kept). Needs **OpenSSL
  3.1.1** (also built here).
- **custom commands** — copied from Gradle's ndkBuild output
  (`app/build/intermediates/cxx/<Variant>/.../obj/local/arm64-v8a`).

**Build order is circular:** the script copies custom commands that only exist
after Gradle's ndkBuild. Sequence: `assembleDebug` (compiles commands) → re-run
the bootstrap script (now packages them) → `assembleDebug` again.

## Native commands (`app/src/main/cpp/`, built by `Android.mk`)

| Binary | Source | Mechanism |
|---|---|---|
| `fontsize` | `fontsize.c` | file-trigger |
| `font` | `font.c` | file-trigger |
| `textcolor` | `textcolor.c` | file-trigger |
| `backgroundcolor` | `backgroundcolor.c` | file-trigger |
| `sysmon` | `sysmon.c` | broadcast (**broken**) |
| `debug_proc` | `debug_proc.c` | prints `/proc/*` (diagnostic) |

Also `libxport-bootstrap.so` (`xport-bootstrap.c`), the JNI bootstrap helper.

### File-trigger pattern (how working commands talk to the app)

The proven mechanism. The C command writes a value file and a trigger file in
the app home (`/data/data/com.xport.terminal/files/home/`), e.g. `.fontsize` +
`.fontsize_changed`. `TermuxActivity` polls the trigger
(`checkAndRefreshFontSize`, `.color_changed`) and applies the change via the
manager classes:

- `FontManager` / fonts in `terminal-view` (GeistMono Regular/Bold/Italic,
  Inter-Thin in `app/src/main/assets/fonts/`).
- `ColorThemeManager` / `xportStyleRenderer` in `terminal-view`.

No `am`, no broadcasts — the minimal shell can't reach Android framework tools.

### sysmon — does NOT work (kept for faithful reproduction)

`sysmon.c` shells out to `am broadcast -a com.xport.terminal.SYSMON`, received by
`SysmonReceiver` → `SystemMonitor` (battery/cpu/mem/uptime via Android APIs).
It never worked: the toybox shell can't reach `am`/`cmd`
(`cmd: inaccessible or not found`), and the receiver is `exported="false"` so no
external uid can reach it either. To fix: rewrite to the file-trigger pattern.

## SSH usage

```
ssh user@host                                  # dbclient
dropbearkey -t ed25519 -f ~/.ssh/id_ed25519    # keygen
scp file user@host:/path/                       # transfer
dropbearconvert ...                             # key format conversion
```

Password/interactive auth is disabled; key-based only (RSA/ECDSA/Ed25519).

## On-device LLM (`llm`)

Run small GGUF models locally via `llama.cpp` (CPU-only). Models are
user-supplied — never bundled in the APK.

```
llm pull qwen2.5:1.5b     # download a model (Ollama-like name)
llm pull <url>            # or any direct GGUF URL
llm list                  # list downloaded models (* = default)
llm run qwen2.5:1.5b      # chat (sets it as default)
llm                       # chat with the default/last model
llm rm <name>             # delete a model
llm names                 # list known names
```

Models live in `~/models`. Known names map to HuggingFace Q4_K_M GGUFs (edit the
`url_for` table in `scripts/llm` to add more). In the chat REPL: `/exit` or
Ctrl+C to quit, `/regen`, `/clear`.

> **Keep XPort in the foreground while `llm pull` downloads.** The download runs
> in the app (see below), driven by a `FileObserver` in `TermuxActivity`. If you
> switch away and Android backgrounds/kills the activity, the download can stall
> or stop. For multi-GB models, leave XPort open until it prints `Done:`. A
> partial download is left as `<model>.part` and is not used; just re-run `llm
> pull` to fetch again.

**Expectations.** ~10–20 t/s for 1–3B Q4 on a flagship; sub-1B is faster, ~4B
slower. Size is RAM-bound: Q4_K_M ≈ 0.5 GB (sub-1B) … 3 GB (3B) … 5 GB
(`gemma4:e4b`). Pick a model that fits your device's free RAM. After ~60–90s of
continuous generation the SoC throttles — short turns are fine, long monologues
degrade. Default flags: `-t 4` (big cores), `-c 2048`.

`gemma4:e4b` (Gemma 4 E4B, Apr 2026) is a strong ~4B with native thinking and
long context — good on ≥8 GB devices; supported by the pinned llama.cpp.

### How it's built (`scripts/build-minimal-bootstrap.sh`, `build_llama`)

A **second, isolated NDK (r27c)** + the SDK's cmake/ninja compile `llama-cli`
(and `llama-bench`), pinned to a fixed llama.cpp commit. This never touches the
r22b chain that builds dropbear/toybox, so their reproducibility is untouched.
`llama-cli` is a shared-lib build: stripped, the binary + 8 `.so`s total ~12 MB,
shipped as `bin/{llama-cli,llama-bench}` + `lib/*.so`. The binary finds its libs
via a baked-in RUNPATH (`<prefix>/lib`) — no `LD_LIBRARY_PATH` needed (the shell
session strips it). `llm` is a plain shell script in `bin/`.

### Why `llm pull` downloads via the app (DNS)

The terminal's forked shell child has **no DNS resolver**: Android resolves via
the process's bound network, and `bindProcessToNetwork()` doesn't survive
`fork()` (the native `android_setprocnetwork` hits `EPERM` in the bare child).
The app process *can* resolve, so `llm pull` hands the download to it — the same
file-trigger pattern as the font commands. `llm` writes `~/.llm_download` (url +
dest), `LlmDownloader` (a `FileObserver` in `TermuxActivity`) fetches it with
`HttpURLConnection` and writes progress to `~/.llm_download_status`, which `llm`
polls.

## History / reproducibility notes

Repo re-cloned May 2026; the whole **sysmon** feature was missing from git and
was recovered (C from the tablet binaries' DWARF/.rodata, Java by decompiling the
installed APK). Source now rebuilds the installed APK: dropbear tools + toybox +
all 6 custom commands are `.text`/`.rodata`-identical, fonts/manifest/cert
identical. Whole-APK hashes never match (build-path in debug metadata + R8).

Older planning notes: `misc/{xport,font,color,styling,alias,sysmon,cleanup}.md`.
