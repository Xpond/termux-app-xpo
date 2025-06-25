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

## History / reproducibility notes

Repo re-cloned May 2026; the whole **sysmon** feature was missing from git and
was recovered (C from the tablet binaries' DWARF/.rodata, Java by decompiling the
installed APK). Source now rebuilds the installed APK: dropbear tools + toybox +
all 6 custom commands are `.text`/`.rodata`-identical, fonts/manifest/cert
identical. Whole-APK hashes never match (build-path in debug metadata + R8).

Older planning notes: `misc/{xport,font,color,styling,alias,sysmon,cleanup}.md`.
