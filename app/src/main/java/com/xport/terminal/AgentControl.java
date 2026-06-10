package com.xport.terminal;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Bridges the `agent` shell wrapper to {@link AgentService}.
 *
 * Polls for the trigger file once a second rather than using a FileObserver:
 * {@link LlmServerControl} already holds the lone FileObserver on app home, and
 * adding a second on the same directory makes CLOSE_WRITE unreliable (it can stop
 * the first from firing — that bit the first agent attempt). A 1Hz poll is
 * bulletproof and plenty fast for a user-typed command. Mirrors {@link LlmDownloader}.
 *
 * Protocol (files under ~):
 *   .agent_start  request: line 1 = goal  -> run the agent loop (fresh conversation)
 *   .agent_msg    request: line 1 = follow-up message into the live conversation
 *   .agent_stop   request: abort the running loop and drop the conversation
 */
public class AgentControl {

    private static final String TAG = "AgentControl";
    private static final String HOME = "/data/data/com.xport.terminal/files/home";
    private static final String START = HOME + "/.agent_start";
    private static final String MSG = HOME + "/.agent_msg";
    private static final String STOP = HOME + "/.agent_stop";

    private final Context mContext;
    private volatile boolean mRunning;

    public AgentControl(Context context) { mContext = context.getApplicationContext(); }

    public void start() {
        mRunning = true;
        new Thread(() -> {
            while (mRunning) {
                handle();
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }, "AgentControl-poll").start();
    }

    public void stop() { mRunning = false; }

    private void handle() {
        File stop = new File(STOP);
        if (stop.exists()) {
            stop.delete();
            try {
                mContext.startService(new Intent(mContext, AgentService.class)
                    .putExtra(AgentService.EXTRA_STOP, true));
            } catch (Exception e) { Log.e(TAG, "stop agent failed", e); }
        }
        forward(new File(START), AgentService.EXTRA_GOAL);
        forward(new File(MSG), AgentService.EXTRA_MSG);
    }

    private void forward(File req, String extra) {
        if (!req.exists()) return;
        String text = readLine(req);
        req.delete();
        if (text.isEmpty()) return;
        try {
            mContext.startService(new Intent(mContext, AgentService.class).putExtra(extra, text));
        } catch (Exception e) { Log.e(TAG, extra + " failed", e); }
    }

    private static String readLine(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l = r.readLine();
            return l == null ? "" : l.trim();
        } catch (Exception e) { return ""; }
    }
}
