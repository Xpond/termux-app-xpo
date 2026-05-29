# XPort

A minimal, SSH-focused Android terminal — a heavily stripped fork of
[Termux](https://github.com/termux/termux-app).

XPort keeps Termux's solid terminal emulator and throws out everything else: no
APT, no compilers, no language runtimes, no editors, no 990+ packages. What's
left is a ~13 MB bootstrap with just the tools needed to be a fast, secure SSH
client on the go — versus Termux's ~180 MB.

> Package `com.xport.terminal` · ARM64 only · Android 5.0+ (API 21+, targets 28)

## Why XPort

- **Tiny.** ~13 MB bootstrap instead of ~180 MB.
- **SSH-first.** Bundles Dropbear 2025.88 (client, scp, keygen, key conversion)
  built against OpenSSL 3.1.1, plus Toybox 0.8.10 for core utils.
- **Stable on modern Android.** Toybox instead of BusyBox avoids the signal-31
  crashes; ARM64 TLS alignment is patched for Bionic.
- **Customizable terminal.** Live font and color-theme switching from the shell
  (`font`, `fontsize`, `textcolor`, `backgroundcolor`), GeistMono + Inter fonts,
  fullscreen viewport.

## SSH usage

```sh
ssh user@host                                # connect (Dropbear dbclient)
dropbearkey -t ed25519 -f ~/.ssh/id_ed25519  # generate a key
scp file user@host:/path/                    # copy files
dropbearconvert ...                          # convert key formats
```

Authentication is key-based (RSA / ECDSA / Ed25519); password auth is disabled.

## Custom commands

| Command | Does |
|---|---|
| `font <variant>` | switch terminal font (GeistMono regular/bold/italic, Inter-thin) |
| `fontsize <1-10>` | adjust font size |
| `textcolor` / `backgroundcolor` | set terminal colors / theme |
| `llm pull/run/list <name>` | run small local LLMs (GGUF via llama.cpp) |

These talk to the app via a file-trigger mechanism: the command writes a small
state file the app watches and applies live.

## On-device LLM

Run small GGUF models locally, offline, CPU-only:

```sh
llm pull qwen2.5:1.5b    # download by name (or a direct GGUF URL)
llm run qwen2.5:1.5b     # chat
llm list                 # downloaded models
```

Models live in `~/models` and are user-supplied (never bundled). Stay ≤3B Q4;
expect ~10–20 t/s on a flagship. Keep XPort in the foreground while `llm pull`
runs — the download happens in the app and can stall if backgrounded. Details in
[`docs/xport.md`](docs/xport.md).

## Building

Requirements:
- Android **NDK 22.1.7171670** (r22b) and `platforms;android-34`
- JDK 17+ (builds on JDK 21), the Gradle wrapper handles the rest
- `local.properties` with `sdk.dir=/path/to/Android/Sdk`

The SSH/utils bootstrap is compiled from source (not committed). Because the
build embeds custom commands compiled by Gradle's NDK step, the bootstrap is
produced in two passes:

```sh
./gradlew :app:assembleDebug          # compiles native commands
./scripts/build-minimal-bootstrap.sh  # builds + packages the bootstrap (incl. commands)
./gradlew :app:assembleDebug          # final APK with the complete bootstrap
```

Output: `app/build/outputs/apk/debug/termux-app_xport-debug_arm64-v8a.apk`.

See [`docs/xport.md`](docs/xport.md) for the full architecture.

## Credits & license

XPort is a fork of [Termux](https://github.com/termux/termux-app) and inherits
its license (GPLv3 + Apache-2.0; see [`LICENSE.md`](LICENSE.md)). All credit for
the underlying terminal emulator and app framework goes to the Termux project
and its contributors. It also bundles
[Toybox](https://landley.net/toybox/), [Dropbear](https://matt.ucc.asn.au/dropbear/dropbear.html),
and [OpenSSL](https://www.openssl.org/), each under their respective licenses.
