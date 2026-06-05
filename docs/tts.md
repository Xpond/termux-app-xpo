# tts — on-device text-to-speech

`tts` turns text into a WAV file entirely on-device — no network at runtime, no
Python. It runs [KittenTTS](https://huggingface.co/KittenML) mini-0.8 (an 80M
StyleTTS2 model) through ONNX Runtime, with espeak-ng for grapheme→phoneme.
English only; the model is user-supplied in `~/models/tts` (not bundled).

## Usage

```sh
tts pull                       # download the model into ~/models/tts (~81 MB)
tts "hello from xport" hi.wav  # synthesize -> hi.wav (default: out.wav)
```

Output is 24 kHz mono 16-bit WAV. The bootstrap has no audio player, so `tts`
only writes the file — play it with anything (or `adb pull` it off). Voice is
Bruno (`expr-voice-3-m`).

> Keep XPort in the foreground while `tts pull` downloads — the fetch runs in the
> app (same DNS reason as `llm pull`, see [xport.md](xport.md#why-llm-pull-downloads-via-the-app-dns)).

## Pipeline

```
text → espeak-ng (IPA) → tokens → ONNX (KittenTTS) → 24kHz float → trim → WAV
```

1. **G2P:** `espeak_TextToPhonemes` (en-us, IPA, with stress) → IPA string.
2. **Tokenize:** map each IPA char to an ID via a fixed 175-symbol table
   (KittenTTS's `TextCleaner`, baked into `vocab_table.h`); wrap `0 … 10 0`.
3. **Inference:** feed `input_ids` (int64), `style` (the voice's `[char_count]`
   row of a 400×256 matrix), and `speed` (1.0). Output `waveform`.
4. **Trim** the last 5000 samples (model artifact), write the WAV by hand.

All in one native binary, `tts-bin` (`scripts/tts-src/tts.c`); `tts` is a shell
wrapper. `tts-bin --extract-voice voices.npz <voice> out.bin` pulls a voice's
matrix out of the npz (a ZIP64 archive of `.npy` arrays) on first use.

## What's shipped vs. downloaded

The bootstrap ships the **engine** (~20 MB): `bin/{tts,tts-bin}`,
`lib/libonnxruntime.so` (18 MB), `lib/libespeak-ng.so` (1.5 MB), and a trimmed
English `share/espeak-ng-data/` (~850 KB — the full set is 19 MB, but 18 MB of
that is other languages). The **model** (`kitten_mini.onnx` 78 MB + `voices.npz`
3 MB) is fetched by `tts pull`, like the `llm` GGUFs.

## How it's built (`scripts/build-minimal-bootstrap.sh`, `build_tts`)

Reuses the LLM's isolated r27c NDK + cmake. ONNX Runtime is the prebuilt
`onnxruntime-android` AAR (no source build). espeak-ng is cloned at the tag
matching the host (`1.52.0` — must match for byte-identical phonemes) and
cross-compiled as a minimal shared lib (all synth/audio backends off; only the
phonemizer is used). `tts-bin` finds its `.so`s via baked-in RUNPATH. The
English espeak data is copied from the host's `/usr/share/espeak-ng-data`.

## Limits

- **English only.** espeak-ng can phonemize other languages, but the model was
  trained on English voices, so non-English sounds English-accented.
- **One sentence at a time, no streaming.** Synthesis is fast (sub-second for a
  short sentence) but the whole WAV is written before it returns.
- **No playback.** Writes a file; pair with a player.
