package com.xport.terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.io.DataInputStream;
import java.io.File;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Speaks a paragraph aloud, sentence by sentence, entirely on-device.
 *
 * The backend half of XPort's read-aloud feature. Given text (from the
 * accessibility reader or any caller), it splits it into sentences and feeds
 * them to a resident `tts-bin --serve` process (KittenTTS): the model loads
 * once, then each sentence written to its stdin returns a block of PCM on its
 * stdout. Two threads decouple synthesis from playback — a synth thread fills a
 * small bounded queue with PCM blocks, a player thread drains it to an
 * {@link AudioTrack} back-to-back — so the speaker never waits on synthesis and
 * there's no gap between sentences. Keeping the model resident removes the long
 * per-sentence gaps a fresh process-per-sentence caused. When the speaker goes
 * idle (everything voiced), it tears down so nothing lingers.
 *
 * It's a foreground service (+ the same 1x1 overlay trick as
 * {@link LlmServerService}) so playback keeps going when you switch to the app
 * you're listening to. No HTTP, no daemon — the caller is in-process; this just
 * owns the queue and the speaker.
 *
 * Driven by Intents:
 *   ACTION_SPEAK  + EXTRA_TEXT  -> enqueue + start speaking
 *   ACTION_STOP                 -> flush the queue, stop instantly, tear down
 */
public class TtsPlayerService extends Service {

    private static final String TAG = "TtsPlayerService";
    private static final String HOME = "/data/data/com.xport.terminal/files/home";
    private static final String USR = "/data/data/com.xport.terminal/files/usr";
    private static final String TTS_BIN = USR + "/bin/tts-bin";
    private static final String SHARE = USR + "/share";              // espeak-ng-data
    private static final String MODEL = HOME + "/models/tts/kitten_mini.onnx";
    private static final String VOICES = HOME + "/models/tts/voices.bin";
    private static final String ESPEAK_VOICE = "en-us";
    private static final String CHANNEL_ID = "xport_tts_player";
    private static final int NOTIF_ID = 4243; // distinct from Termux + LlmServerService
    private static final int SAMPLE_RATE = 24000; // KittenTTS output

    public static final String ACTION_SPEAK = "com.xport.terminal.TTS_SPEAK";
    public static final String ACTION_STOP = "com.xport.terminal.TTS_STOP";
    public static final String EXTRA_TEXT = "text";

    /** Start (or feed) the player with text to speak. The single entry point for
     *  every frontend — the screen reader, the "Speak" menu item, the clipboard
     *  reader — so none of them repeat the intent shape. No-op on blank text. */
    public static void speak(Context ctx, String text) {
        if (text == null || text.trim().isEmpty()) return;
        ctx.startService(new Intent(ctx, TtsPlayerService.class)
            .setAction(ACTION_SPEAK).putExtra(EXTRA_TEXT, text.trim()));
    }

    /** Fired when the speaker goes idle (everything fed has been voiced), so the
     *  reader/button can reset state. Cleared on stop. */
    public static volatile Runnable sOnIdle;

    // A sentinel byte[0] pushed onto the PCM queue to mark "stop" for the player.
    private static final byte[] EOS = new byte[0];

    private final LinkedBlockingQueue<String> mQueue = new LinkedBlockingQueue<>();
    // Synthesized PCM blocks waiting to play. Bounded (look-ahead of 2) so synth
    // runs ahead of the speaker — it's ~10x real-time — without unbounded memory.
    private final LinkedBlockingQueue<byte[]> mPcmQueue = new LinkedBlockingQueue<>(2);
    private final AtomicBoolean mStopped = new AtomicBoolean(false);
    // Sentences offered but not yet synthesized. The player must not call it idle
    // while this is > 0: at startup the worker pulls a sentence out of mQueue and
    // spends ~1s loading the model before any PCM appears — both queues are empty
    // in that gap, which used to look "done" and tear down before the first word.
    private final AtomicInteger mPending = new AtomicInteger(0);
    private Thread mWorker;   // synth: sentence -> PCM block -> mPcmQueue
    private Thread mPlayer;   // playback: mPcmQueue -> AudioTrack, continuous
    private AudioTrack mTrack;
    private View mOverlay;
    private Process mServe;          // resident tts-bin --serve (model loaded once)
    private OutputStream mServeIn;   // its stdin  — we write a sentence per line
    private DataInputStream mServeOut; // its stdout — length-prefixed PCM blocks

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) { teardown(); return START_NOT_STICKY; }

        String text = intent != null ? intent.getStringExtra(EXTRA_TEXT) : null;
        if (text == null || text.trim().isEmpty()) return START_NOT_STICKY;

        if (mWorker == null) {
            mStopped.set(false); // clear the flag a previous teardown left set
            startForeground(NOTIF_ID, buildNotification());
            addOverlay();
            startWorker();
            startPlayer();
        }
        for (String s : splitSentences(text)) { mPending.incrementAndGet(); mQueue.offer(s); }
        return START_STICKY;
    }

    /** Naive sentence split: break after . ! ? (and newlines). Good enough — the
     *  synthesizer just needs bite-sized chunks; precision isn't required here. */
    private static String[] splitSentences(String text) {
        return text.replaceAll("\\s+", " ").trim().split("(?<=[.!?])\\s+|\\n+");
    }

    /** Synth thread: pull a sentence, synthesize it to a PCM block, queue it. */
    private void startWorker() {
        mWorker = new Thread(() -> {
            try {
                if (!startServe()) { mPcmQueue.put(EOS); return; }
                while (!mStopped.get()) {
                    String sentence = mQueue.take(); // blocks until text arrives
                    if (mStopped.get()) break;
                    byte[] pcm = synth(sentence);
                    // Decrement only after synth returns: the sentence is now
                    // accounted for by a PCM block (or a synth failure), so the
                    // player won't see a false "idle" while we were still working.
                    if (pcm != null && pcm.length > 0) mPcmQueue.put(pcm); // backpressure
                    mPending.decrementAndGet();
                }
            } catch (InterruptedException ignored) {}
        });
        mWorker.start();
    }

    /** Player thread: take PCM blocks and write them to AudioTrack back-to-back.
     *  Because the next block is already synthesized and waiting, there's no gap
     *  between sentences — the speaker never waits on synthesis. */
    private void startPlayer() {
        mPlayer = new Thread(() -> {
            try {
                ensureTrack();
                mTrack.play();
                while (!mStopped.get()) {
                    byte[] pcm = mPcmQueue.poll(200, TimeUnit.MILLISECONDS);
                    if (pcm == null) {
                        // Nothing to play and synthesis is idle: reading is done.
                        // Reset the button, then fully tear down — holding the
                        // resident tts-bin (~320MB) and the foreground overlay
                        // after reading wastes CPU/RAM and (with the overlay's
                        // "visible" scheduling) makes the app laggy. Next read
                        // respawns; the ~1s model reload is a fine per-session cost.
                        // mPending guards the startup gap (model still loading).
                        if (mQueue.isEmpty() && mPending.get() == 0) {
                            Runnable cb = sOnIdle;
                            if (cb != null) cb.run();
                            teardown();
                            break;
                        }
                        continue;
                    }
                    if (pcm == EOS || mStopped.get()) break;
                    int off = 0;
                    while (off < pcm.length && !mStopped.get()) {
                        int w = mTrack.write(pcm, off, pcm.length - off);
                        if (w <= 0) break;
                        off += w;
                    }
                }
            } catch (InterruptedException ignored) {}
        });
        mPlayer.start();
    }

    /** Spawn the resident synthesizer; the 78MB model loads once, here. */
    private boolean startServe() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                TTS_BIN, SHARE, MODEL, VOICES, ESPEAK_VOICE, "--serve");
            pb.directory(new File(HOME));
            mServe = pb.start();
            mServeIn = mServe.getOutputStream();
            mServeOut = new DataInputStream(mServe.getInputStream());
            // PCM comes on stdout; espeak/ORT log to stderr — drain it so the
            // child never blocks on a full stderr pipe. (Can't merge the streams:
            // that would corrupt the binary PCM.)
            drainStderr(mServe);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "startServe failed", e);
            return false;
        }
    }

    /**
     * Write one sentence to the synth's stdin and read back its full PCM block.
     * Protocol: a little-endian uint32 byte count, then that many bytes of 16-bit
     * mono PCM (0 = synth failure). Returns the PCM, or null on error.
     */
    private byte[] synth(String sentence) {
        OutputStream in = mServeIn;
        DataInputStream out = mServeOut;
        if (in == null || out == null) return null; // torn down under us
        try {
            in.write(sentence.getBytes("UTF-8"));
            in.write('\n');
            in.flush();
            int n = readLE32(out);
            if (n <= 0) return null;
            byte[] pcm = new byte[n];
            out.readFully(pcm);
            return pcm;
        } catch (Exception e) {
            // A teardown (stop / idle) closes the pipe under us mid-read — that's
            // the InterruptedIOException, expected, not a failure. Only log a real
            // synth error.
            if (!mStopped.get()) Log.e(TAG, "synth failed", e);
            return null;
        }
    }

    /** Read a little-endian uint32 (the PCM byte count) from the synth's stdout. */
    private static int readLE32(DataInputStream in) throws java.io.IOException {
        int b0 = in.read(), b1 = in.read(), b2 = in.read(), b3 = in.read();
        if ((b0 | b1 | b2 | b3) < 0) throw new java.io.EOFException();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private void drainStderr(Process p) {
        final java.io.InputStream err = p.getErrorStream();
        new Thread(() -> {
            byte[] b = new byte[512];
            try { while (err.read(b) >= 0) { /* discard */ } } catch (Exception ignored) {}
        }).start();
    }

    private void ensureTrack() {
        if (mTrack != null) return;
        int min = AudioTrack.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        mTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            Math.max(min, SAMPLE_RATE), AudioTrack.MODE_STREAM);
    }

    /** Flush queues, stop the speaker now, end both threads + synth, drop overlay. */
    private void teardown() {
        mStopped.set(true);
        mPending.set(0);
        mQueue.clear();
        mPcmQueue.clear();
        mPcmQueue.offer(EOS); // unblock the player if it's waiting on take()
        if (mTrack != null) {
            try { mTrack.pause(); mTrack.flush(); mTrack.release(); } catch (Exception ignored) {}
            mTrack = null;
        }
        stopServe();
        if (mWorker != null) { mWorker.interrupt(); mWorker = null; }
        if (mPlayer != null) { mPlayer.interrupt(); mPlayer = null; }
        removeOverlay();
        stopForeground(true);
        stopSelf();
    }

    /**
     * Stop the resident synth. Close its stdin FIRST so its serve loop sees EOF
     * and returns through tts-bin's `_exit(0)` — skipping the C++ static
     * destructors. SIGTERM (destroy()) instead runs them, and ONNX Runtime's
     * global dtors touch an already-destroyed mutex → SIGABRT on every offload
     * (the crashy "model offload" we saw). destroy() stays only as a fallback if
     * it doesn't exit promptly.
     */
    private void stopServe() {
        Process p = mServe;
        OutputStream in = mServeIn;
        mServe = null; mServeIn = null; mServeOut = null;
        if (in != null) { try { in.close(); } catch (Exception ignored) {} }
        if (p != null) {
            try {
                if (!p.waitFor(500, TimeUnit.MILLISECONDS)) p.destroy();
            } catch (Exception e) { p.destroy(); }
        }
    }

    // --- overlay: keep playback in the /foreground cpuset when backgrounded.
    //     Same trick (and rationale) as LlmServerService.addOverlay(). ---
    private void addOverlay() {
        if (mOverlay != null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return;
        try {
            int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
              | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
              | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            mOverlay = new View(this);
            ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).addView(mOverlay, lp);
        } catch (Exception e) { mOverlay = null; }
    }

    private void removeOverlay() {
        if (mOverlay == null) return;
        try {
            ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).removeView(mOverlay);
        } catch (Exception ignored) {}
        mOverlay = null;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "Read aloud", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                .createNotificationChannel(ch);
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        return b.setContentTitle("Reading aloud")
                .setContentText("Speaking the current screen")
                .setSmallIcon(R.drawable.ic_service_notification)
                .setColor(0xFF607D8B)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    @Override
    public void onDestroy() {
        mStopped.set(true);
        mPcmQueue.offer(EOS);
        if (mWorker != null) mWorker.interrupt();
        if (mPlayer != null) mPlayer.interrupt();
        if (mTrack != null) { try { mTrack.release(); } catch (Exception ignored) {} mTrack = null; }
        stopServe();
        removeOverlay();
        super.onDestroy();
    }
}
