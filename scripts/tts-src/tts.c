// End-to-end: text -> espeak IPA -> tokens -> KittenTTS (mini-0.8) ONNX -> WAV.
// No Python. Links libespeak-ng.so + libonnxruntime.so.
//
// Usage: tts <data_path> <onnx> <voice.bin> <espeak_voice> <text> <out.wav>
//   data_path     dir holding espeak-ng-data
//   onnx          kitten .onnx
//   voice.bin     400x256 float32 voice matrix (one style row per char count)
//   espeak_voice  e.g. en-us
//   text          input text (UTF-8)

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include "speak_lib.h"
#include "onnxruntime_c_api.h"
#include "vocab_table.h"

#define TRIM_TAIL 5000   // mini-0.8 trims 5000 samples off the tail
#define SAMPLE_RATE 24000

static const OrtApi *g_ort = NULL;
static void check(OrtStatus *st, const char *what) {
    if (st) { fprintf(stderr, "ORT %s: %s\n", what, g_ort->GetErrorMessage(st));
              g_ort->ReleaseStatus(st); exit(1); }
}

// --- vocab lookup: Unicode code point -> token id (binary search VOCAB) ---
static int lookup(unsigned int cp) {
    int lo = 0, hi = VOCAB_N - 1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        if (VOCAB[mid].cp == cp) return VOCAB[mid].id;
        if (VOCAB[mid].cp < cp) lo = mid + 1; else hi = mid - 1;
    }
    return -1;  // not in vocab -> drop
}

// decode one UTF-8 code point from s; advance *s; return cp or 0 at end.
static unsigned int utf8_next(const char **s) {
    const unsigned char *p = (const unsigned char *)*s;
    if (*p == 0) return 0;
    unsigned int cp; int n;
    if (*p < 0x80) { cp = *p; n = 1; }
    else if ((*p & 0xE0) == 0xC0) { cp = *p & 0x1F; n = 2; }
    else if ((*p & 0xF0) == 0xE0) { cp = *p & 0x0F; n = 3; }
    else if ((*p & 0xF8) == 0xF0) { cp = *p & 0x07; n = 4; }
    else { *s += 1; return 0xFFFD; }
    for (int i = 1; i < n; i++) cp = (cp << 6) | (p[i] & 0x3F);
    *s += n;
    return cp;
}

// is this code point a "word char" (\w) or space, for the regex normalize?
static int is_word(unsigned int cp) {
    if (cp == '_') return 1;
    if (cp >= '0' && cp <= '9') return 1;
    if (cp >= 'A' && cp <= 'Z') return 1;
    if (cp >= 'a' && cp <= 'z') return 1;
    return cp > 127;  // espeak IPA letters are non-ASCII -> treat as word chars
}

// Reproduce: " ".join(re.findall(r"\w+|[^\w\s]", ipa)) then char->id, wrap 0.
// We stream: emit a space between a run of word-chars and a following
// punctuation token (and vice-versa), collapsing original whitespace.
static size_t tokenize(const char *ipa, int64_t *out, size_t cap) {
    size_t n = 0;
    out[n++] = 0;  // leading pad
    const char *p = ipa;
    int prev_emitted = 0;     // did we emit any token yet (in body)?
    int prev_was_word = -1;   // -1 none, 0 punct, 1 word
    unsigned int cp;
    while ((cp = utf8_next(&p)) != 0) {
        // whitespace: ends current run, no token
        if (cp == ' ' || cp == '\t' || cp == '\n' || cp == '\r') {
            if (prev_was_word != -1) prev_was_word = 2;  // pending separator
            continue;
        }
        int word = is_word(cp);
        // insert a space token between tokens per "join with ' '":
        // findall yields word-runs and single punct as separate tokens; joined
        // by space. So a space goes between any two adjacent emitted tokens
        // UNLESS both are word chars in the same contiguous run.
        if (prev_emitted) {
            int need_space;
            if (prev_was_word == 2) need_space = 1;        // had whitespace
            else if (prev_was_word == 1 && word) need_space = 0;  // same word run
            else need_space = 1;                            // run boundary
            if (need_space) { int sid = lookup(' '); if (sid >= 0 && n < cap) out[n++] = sid; }
        }
        int id = lookup(cp);
        if (id >= 0 && n < cap) out[n++] = id;
        prev_emitted = 1;
        prev_was_word = word ? 1 : 0;
    }
    if (n + 1 < cap) { out[n++] = 10; out[n++] = 0; }  // 0.8 wrap: ...,10,0
    return n;
}

static void write_wav(const char *path, const float *s, size_t n, int sr) {
    FILE *f = fopen(path, "wb"); if (!f) { perror("fopen"); exit(1); }
    uint32_t data = (uint32_t)(n * 2), riff = 36 + data, br = (uint32_t)sr * 2, srate = sr, s1 = 16;
    uint16_t fmt = 1, ch = 1, bits = 16, blk = 2;
    fwrite("RIFF",1,4,f); fwrite(&riff,4,1,f); fwrite("WAVE",1,4,f);
    fwrite("fmt ",1,4,f); fwrite(&s1,4,1,f); fwrite(&fmt,2,1,f); fwrite(&ch,2,1,f);
    fwrite(&srate,4,1,f); fwrite(&br,4,1,f); fwrite(&blk,2,1,f); fwrite(&bits,2,1,f);
    fwrite("data",1,4,f); fwrite(&data,4,1,f);
    for (size_t i=0;i<n;i++){ float v=s[i]; if(v>1)v=1; else if(v<-1)v=-1;
        int16_t x=(int16_t)(v*32767.0f); fwrite(&x,2,1,f);} fclose(f);
}

// Extract one voice's raw (400x256) float32 matrix from a KittenTTS voices.npz.
// .npz = a STORED (uncompressed) zip of "<voice>.npy" files; each .npy is a
// 128-byte header + 409600 bytes of <f4 data. We scan local file headers for
// "<voice>.npy", skip its npy header, and copy the matrix bytes out.
#define NPY_HDR 128
#define MATRIX_BYTES (400 * 256 * 4)
static int read_u32le(const unsigned char *p) {
    return p[0] | (p[1]<<8) | (p[2]<<16) | (p[3]<<24);
}
// Read the compressed size, honoring ZIP64: if the 32-bit field is 0xFFFFFFFF,
// the real 64-bit size lives in the extra field (header id 0x0001).
static long entry_csize(unsigned int csize32, const unsigned char *extra, int elen) {
    if (csize32 != 0xFFFFFFFFu) return csize32;
    int off = 0;
    while (off + 4 <= elen) {
        int eid = extra[off] | (extra[off+1]<<8);
        int esz = extra[off+2] | (extra[off+3]<<8);
        if (eid == 0x0001 && esz >= 16) {
            // order: uncompressed(8), compressed(8) — we want compressed (2nd)
            const unsigned char *p = extra + off + 4 + 8;
            long v = 0; for (int i = 7; i >= 0; i--) v = (v<<8) | p[i];
            return v;
        }
        off += 4 + esz;
    }
    return csize32;
}
static int extract_voice(const char *npz, const char *voice, const char *out) {
    FILE *f = fopen(npz, "rb");
    if (!f) { fprintf(stderr, "open %s failed\n", npz); return 1; }
    char want[128]; snprintf(want, sizeof(want), "%s.npy", voice);
    size_t wlen = strlen(want);
    unsigned char h[30];
    while (fread(h, 1, 30, f) == 30) {
        if (read_u32le(h) != 0x04034b50) break;  // local file header sig
        int comp = h[8] | (h[9]<<8);
        unsigned int csize32 = (unsigned int)read_u32le(h + 18);
        int nlen = h[26] | (h[27]<<8);
        int elen = h[28] | (h[29]<<8);
        char name[256]; if (nlen >= (int)sizeof(name)) nlen = sizeof(name)-1;
        if (fread(name, 1, nlen, f) != (size_t)nlen) break;
        name[nlen] = 0;
        unsigned char extra[256]; if (elen > (int)sizeof(extra)) { fclose(f); return 1; }
        if (fread(extra, 1, elen, f) != (size_t)elen) break;
        long data_off = ftell(f);  // data starts right after name + extra
        if ((size_t)nlen == wlen && memcmp(name, want, wlen) == 0 && comp == 0) {
            fseek(f, data_off + NPY_HDR, SEEK_SET);
            static unsigned char buf[MATRIX_BYTES];
            if (fread(buf, 1, MATRIX_BYTES, f) != MATRIX_BYTES) {
                fprintf(stderr, "short read for %s\n", want); fclose(f); return 1; }
            fclose(f);
            FILE *o = fopen(out, "wb");
            if (!o) { fprintf(stderr, "open %s failed\n", out); return 1; }
            fwrite(buf, 1, MATRIX_BYTES, o); fclose(o);
            return 0;
        }
        fseek(f, data_off + entry_csize(csize32, extra, elen), SEEK_SET);
    }
    fclose(f);
    fprintf(stderr, "voice '%s' not found in %s\n", voice, npz);
    return 1;
}

int main(int argc, char **argv) {
    if (argc == 5 && strcmp(argv[1], "--extract-voice") == 0)
        return extract_voice(argv[2], argv[3], argv[4]);  // npz voice out.bin
    if (argc != 7) {
        fprintf(stderr, "usage: %s <data_path> <onnx> <voice.bin> <espeak_voice> <text> <out.wav>\n", argv[0]);
        fprintf(stderr, "   or: %s --extract-voice <voices.npz> <voice> <out.bin>\n", argv[0]);
        return 1;
    }
    const char *data_path=argv[1], *onnx=argv[2], *vbin=argv[3],
               *evoice=argv[4], *text=argv[5], *out=argv[6];

    // 1. espeak: text -> IPA (loop over clauses)
    if (espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, data_path,
            espeakINITIALIZE_PHONEME_IPA | espeakINITIALIZE_DONT_EXIT) == EE_INTERNAL_ERROR) {
        fprintf(stderr, "espeak_Initialize failed (data at %s?)\n", data_path); return 1; }
    if (espeak_SetVoiceByName(evoice) != EE_OK) {
        fprintf(stderr, "espeak voice '%s' not found\n", evoice); return 1; }
    char ipa[8192]; ipa[0] = 0; size_t il = 0;
    const void *tp = text;
    while (tp != NULL) {
        const char *ph = espeak_TextToPhonemes(&tp, espeakCHARS_UTF8, 0x02);
        if (ph) { size_t l = strlen(ph); if (il + l + 1 < sizeof(ipa)) { memcpy(ipa+il, ph, l); il += l; ipa[il]=0; } }
        if (tp != NULL && *(const char*)tp != '\0') { if (il+1 < sizeof(ipa)) { ipa[il++]=' '; ipa[il]=0; } }
        else break;
    }
    // NB: don't espeak_Terminate() — it destroys an internal mutex that ORT's
    // static teardown later touches (FORTIFY abort). OS reclaims on exit.

    // 2. tokenize
    int64_t tokens[4096];
    size_t nt = tokenize(ipa, tokens, 4096);

    // 3. load voice matrix (400x256) and pick row = min(char_count, 399).
    //    char_count = Unicode code points of the input text (Python len()).
    static float voices[400 * 256];
    FILE *vf = fopen(vbin, "rb");
    if (!vf || fread(voices, 4, 400 * 256, vf) != 400 * 256) {
        fprintf(stderr, "bad voice file (need 400x256 float32)\n"); return 1; }
    fclose(vf);
    size_t cc = 0; { const char *q = text; while (utf8_next(&q)) cc++; }
    size_t row = cc < 399 ? cc : 399;
    float *style = voices + row * 256;

    // 4. ONNX
    g_ort = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    OrtEnv *env; check(g_ort->CreateEnv(ORT_LOGGING_LEVEL_WARNING,"tts",&env),"env");
    OrtSessionOptions *o; check(g_ort->CreateSessionOptions(&o),"opt");
    (void)g_ort->SetIntraOpNumThreads(o,4);
    OrtSession *s; check(g_ort->CreateSession(env,onnx,o,&s),"session");
    OrtMemoryInfo *m; check(g_ort->CreateCpuMemoryInfo(OrtArenaAllocator,OrtMemTypeDefault,&m),"mem");
    int64_t ids_sh[2]={1,(int64_t)nt}; OrtValue *it;
    check(g_ort->CreateTensorWithDataAsOrtValue(m,tokens,nt*8,ids_sh,2,ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64,&it),"ids");
    int64_t st_sh[2]={1,256}; OrtValue *stt;
    check(g_ort->CreateTensorWithDataAsOrtValue(m,style,256*4,st_sh,2,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,&stt),"style");
    float sp=1.0f; int64_t sp_sh[1]={1}; OrtValue *spt;
    check(g_ort->CreateTensorWithDataAsOrtValue(m,&sp,4,sp_sh,1,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,&spt),"speed");
    const char *in[]={"input_ids","style","speed"}; const OrtValue *iv[]={it,stt,spt};
    const char *on[]={"waveform"}; OrtValue *ov=NULL;
    check(g_ort->Run(s,NULL,in,iv,3,on,1,&ov),"run");
    float *wav; check(g_ort->GetTensorMutableData(ov,(void**)&wav),"data");
    OrtTensorTypeAndShapeInfo *ti; check(g_ort->GetTensorTypeAndShape(ov,&ti),"shape");
    size_t total; check(g_ort->GetTensorShapeElementCount(ti,&total),"count");
    g_ort->ReleaseTensorTypeAndShapeInfo(ti);
    if (total <= TRIM_TAIL){fprintf(stderr,"output too short\n");return 1;}
    size_t nn = total - TRIM_TAIL;
    write_wav(out, wav, nn, SAMPLE_RATE);
    fprintf(stderr, "wrote %s (%.2fs)\n", out, (double)nn/SAMPLE_RATE);
    fflush(NULL);
    // _exit: skip atexit/static dtors. espeak-ng + ORT both register teardown
    // that races on espeak's internal mutex (FORTIFY abort). Output is already
    // flushed; the OS reclaims everything. Clean exit code, no race.
    _exit(0);
}
