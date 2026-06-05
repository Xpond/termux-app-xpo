package com.xport.terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Keeps llama-server alive while XPort is backgrounded so other apps on the
 * device can use the local OpenAI-compatible API on 127.0.0.1.
 *
 * Thin supervisor only: it posts a foreground notification and spawns/holds the
 * llama-server child process. It never speaks HTTP or does inference — the
 * server does all of that. XPort targets API 28, so no foregroundServiceType /
 * specialUse machinery is needed; plain FOREGROUND_SERVICE + a notification.
 *
 * A foreground service keeps the process alive but Android still schedules it in
 * the /background cpuset (little cores) when XPort isn't on screen, throttling
 * inference ~4x. To run full-speed in the background the service holds a 1x1
 * transparent overlay window, which raises the process to a "visible" state and
 * the /foreground cpuset (all cores). See {@link #addOverlay()}.
 *
 * Driven by file triggers the `llmd` wrapper writes (see LlmServerControl):
 * the activity reads .llmd_start and starts this with model/port/token extras.
 */
public class LlmServerService extends Service {

    private static final String TAG = "LlmServerService";
    private static final String HOME = "/data/data/com.xport.terminal/files/home";
    private static final String PREFIX = "/data/data/com.xport.terminal/files/usr";
    private static final String STATUS = HOME + "/.llmd_status";
    private static final String LOG = HOME + "/.llmd_log";
    private static final String CHANNEL_ID = "xport_llm_server";
    private static final int NOTIF_ID = 4242; // distinct from TermuxService's id

    public static final String ACTION_STOP = "com.xport.terminal.LLMD_STOP";
    public static final String EXTRA_MODEL = "model";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_TOKEN = "token";

    private Process mProc;
    private String mPort = "8080";
    private View mOverlay;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            teardown("STOPPED");
            return START_NOT_STICKY;
        }

        String model = intent != null ? intent.getStringExtra(EXTRA_MODEL) : null;
        mPort = intent != null ? intent.getStringExtra(EXTRA_PORT) : "8080";
        String token = intent != null ? intent.getStringExtra(EXTRA_TOKEN) : "";
        if (model == null || !new File(model).exists()) {
            writeStatus("ERROR model not found");
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildNotification(model));
        boolean ok = startServer(model, mPort, token);
        if (!ok) {
            writeStatus("ERROR could not start llama-server");
            stopForeground(true); stopSelf(); return START_NOT_STICKY;
        }
        // A foreground service alone gets oom_adj 200 -> the /background cpuset
        // (little cores), throttling inference ~4x when XPort isn't on screen.
        // A visible (overlay) window raises the process to VISIBLE_APP_ADJ (100)
        // -> /foreground cpuset (all cores), so the server runs full-speed in the
        // background. Without the "draw over other apps" permission we still run,
        // just throttled when backgrounded — report that so `llmd` can warn.
        boolean fast = addOverlay();
        writeStatus(fast ? "RUNNING" : "RUNNING_SLOW");
        // If the child dies on its own (OOM, crash), tear the service down too.
        watchProcess();
        return START_STICKY;
    }

    private boolean startServer(String model, String port, String token) {
        stopServer();
        try {
            // Match the `llm` CLI flags exactly (-t 4 -c 2048) for the same
            // single-stream throughput. No --parallel: it splits the context
            // into slots and roughly halves single-request t/s on a phone,
            // which is all we serve (one generation at a time).
            ProcessBuilder pb = new ProcessBuilder(
                PREFIX + "/bin/llama-server",
                "-m", model,
                "--host", "127.0.0.1",
                "--port", port,
                "--api-key", token,
                "-c", "2048",
                "-t", "4");
            // RUNPATH in the binary points at usr/lib, so no LD_LIBRARY_PATH.
            pb.redirectErrorStream(true);
            pb.redirectOutput(new File(LOG));
            mProc = pb.start();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "startServer failed", e);
            return false;
        }
    }

    private void watchProcess() {
        final Process p = mProc;
        if (p == null) return;
        new Thread(() -> {
            try { p.waitFor(); } catch (InterruptedException ignored) {}
            if (mProc == p) teardown("STOPPED"); // died on its own (OOM, crash)
        }).start();
    }

    private void stopServer() {
        if (mProc != null) { mProc.destroy(); mProc = null; }
    }

    /** Kill the server, drop the overlay + notification, and stop the service. */
    private void teardown(String status) {
        stopServer();
        removeOverlay();
        writeStatus(status);
        stopForeground(true);
        stopSelf();
    }

    /**
     * Add a 1x1 transparent, non-touchable overlay so the process counts as
     * having a visible window (VISIBLE_APP_ADJ) -> /foreground cpuset -> all
     * cores even when XPort is backgrounded. No-op if the overlay permission
     * isn't granted (the server still runs, just throttled when backgrounded).
     */
    private boolean addOverlay() {
        if (mOverlay != null) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "no overlay permission — background inference will be throttled");
            return false;
        }
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
            return true;
        } catch (Exception e) {
            Log.e(TAG, "addOverlay failed", e);
            mOverlay = null;
            return false;
        }
    }

    private void removeOverlay() {
        if (mOverlay == null) return;
        try {
            ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).removeView(mOverlay);
        } catch (Exception ignored) {}
        mOverlay = null;
    }

    private Notification buildNotification(String model) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "LLM API server", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                .createNotificationChannel(ch);
        }
        String name = new File(model).getName();
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        return b.setContentTitle("LLM API on 127.0.0.1:" + mPort)
                .setContentText(name + " — holding RAM for local apps")
                .setSmallIcon(R.drawable.ic_service_notification)
                .setColor(0xFF607D8B)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private void writeStatus(String s) {
        try (FileOutputStream out = new FileOutputStream(STATUS)) {
            out.write(s.getBytes());
        } catch (Exception e) { /* best effort */ }
    }

    @Override
    public void onDestroy() {
        stopServer();
        removeOverlay();
        super.onDestroy();
    }
}
