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
- **sysmon is broken** (uses `am broadcast`, unreachable from the shell). Kept
  only for faithful reproduction; rewrite to file-trigger to fix.

---

# Using `rb` (ResourceBot)

`rb` is your memory of this repo — every Claude Code session, every edit, every
commit, queryable from the shell. Reach for it before you grep, before you read
a file end to end, before you re-discover anything.

The work moves in one direction, and so do the commands:

**You land in the repo.** `rb map` — what exists, where the work has been
happening. Then `rb outline <file>` — what's inside a file, without reading it.

**You wonder how it got this way.** `rb log <file>` — its edit history, newest
first. `rb at <file> <point>` — what it looked like before, or a diff between two
moments (`point` = edit number, timestamp, or `0` for creation).

**You're hunting something specific.** `rb search <keyword> [path]` — every edit
that touched it, across the repo or scoped to a path.

**You step back to get your bearings.** `rb recent` — everything that's changed
lately, grouped by session. `rb sessions` — the index of recent work, newest
first.

**You want the *why*.** Every edit line is tagged `[s#N]`. Follow it:
`rb session N` gives the story — what you were trying to do, what got decided,
what files it touched.

**You connect work to what shipped.** Edits also carry `[c:hash]` when they
landed in a commit. `rb commits [hash]` resolves a commit back to the sessions
behind it.

**You're about to change something and want the blast radius.**
`rb impact <file>` — how it's wired in, what tends to change alongside it, and
where the wiring and the history disagree.

---

That's the whole loop: **map → outline → log → at → search → recent → sessions →
session → commits → impact.**

Two tags thread it together: `[s#N]` → `rb session N` for the why,
`[c:hash]` → `rb commits hash` for what shipped. Partial paths resolve. History
reads newest-first. When in doubt, ask `rb` before you read the source.

---

