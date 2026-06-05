package com.xport.terminal;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.FileObserver;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Bridges the `llmd` shell wrapper to {@link LlmServerService}.
 *
 * The minimal shell can't reach am/broadcasts, so `llmd` writes trigger files in
 * app home and this FileObserver (started from the activity, same pattern as
 * {@link LlmDownloader}) picks them up and starts/stops the service. `llmd start`
 * runs while XPort is foregrounded, so startForegroundService() is permitted.
 *
 * Protocol (files under ~):
 *   .llmd_start  line1 = model path, line2 = port, line3 = api token  -> start
 *   .llmd_stop   (any write)                                          -> stop
 */
public class LlmServerControl {

    private static final String TAG = "LlmServerControl";
    private static final String HOME = "/data/data/com.xport.terminal/files/home";
    private static final String START = HOME + "/.llmd_start";
    private static final String STOP = HOME + "/.llmd_stop";

    private final Context mContext;
    private FileObserver mObserver;

    public LlmServerControl(Context context) { mContext = context.getApplicationContext(); }

    public void start() {
        mObserver = new FileObserver(HOME, FileObserver.CLOSE_WRITE) {
            @Override public void onEvent(int event, String path) {
                if (".llmd_start".equals(path)) handleStart();
                else if (".llmd_stop".equals(path)) handleStop();
            }
        };
        mObserver.startWatching();
        // Handle a request that may already be waiting from before we started.
        if (new File(START).exists()) handleStart();
    }

    public void stop() {
        if (mObserver != null) { mObserver.stopWatching(); mObserver = null; }
    }

    private void handleStart() {
        File req = new File(START);
        if (!req.exists()) return;
        String[] lines = readLines(req);
        req.delete();
        if (lines == null || lines[0].isEmpty()) {
            writeStatus("ERROR empty start request");
            return;
        }
        Intent i = new Intent(mContext, LlmServerService.class)
            .putExtra(LlmServerService.EXTRA_MODEL, lines[0])
            .putExtra(LlmServerService.EXTRA_PORT, lines[1].isEmpty() ? "8080" : lines[1])
            .putExtra(LlmServerService.EXTRA_TOKEN, lines[2]);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                mContext.startForegroundService(i);
            else
                mContext.startService(i);
        } catch (Exception e) {
            Log.e(TAG, "startForegroundService failed", e);
            writeStatus("ERROR " + e.getMessage());
        }
    }

    private void handleStop() {
        new File(STOP).delete();
        Intent i = new Intent(mContext, LlmServerService.class)
            .setAction(LlmServerService.ACTION_STOP);
        try { mContext.startService(i); }
        catch (Exception e) { Log.e(TAG, "stop failed", e); }
    }

    private static String[] readLines(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String a = r.readLine(), b = r.readLine(), c = r.readLine();
            return new String[]{ a == null ? "" : a.trim(),
                                 b == null ? "" : b.trim(),
                                 c == null ? "" : c.trim() };
        } catch (Exception e) { return null; }
    }

    private void writeStatus(String s) {
        try (java.io.FileOutputStream out =
                 new java.io.FileOutputStream(HOME + "/.llmd_status")) {
            out.write(s.getBytes());
        } catch (Exception e) { /* best effort */ }
    }
}
