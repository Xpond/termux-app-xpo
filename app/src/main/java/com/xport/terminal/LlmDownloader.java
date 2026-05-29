package com.xport.terminal;

import android.os.FileObserver;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads GGUF models on behalf of the `llm` shell wrapper.
 *
 * The terminal's forked shell child has no DNS resolver (Android resolves via
 * the process's bound network, which doesn't survive fork()), so wget can't
 * fetch by hostname. The app process CAN resolve, so `llm pull` hands the job
 * here via a trigger file and we download with HttpURLConnection.
 *
 * Protocol (files under ~):
 *   .llm_download         request: line 1 = url, line 2 = destPath
 *   .llm_download_status  progress: "DOWNLOADING <pct>" | "DONE" | "ERROR <msg>"
 */
public class LlmDownloader {

    private static final String HOME = "/data/data/com.xport.terminal/files/home";
    private static final String REQUEST = HOME + "/.llm_download";
    private static final String STATUS = HOME + "/.llm_download_status";

    private FileObserver mObserver;

    /** Start watching for download requests. Call once from the activity. */
    public void start() {
        // CLOSE_WRITE fires after `llm` finishes writing the request file
        // (CREATE would fire on the empty file before the content is written).
        mObserver = new FileObserver(HOME, FileObserver.CLOSE_WRITE) {
            @Override public void onEvent(int event, String path) {
                if (".llm_download".equals(path)) handleRequest();
            }
        };
        mObserver.startWatching();
        // Handle a request that may already be waiting from before we started.
        if (new File(REQUEST).exists()) handleRequest();
    }

    public void stop() {
        if (mObserver != null) { mObserver.stopWatching(); mObserver = null; }
    }

    private void handleRequest() {
        final File req = new File(REQUEST);
        if (!req.exists()) return;
        new Thread(() -> {
            try {
                String[] lines = readLines(req);
                req.delete();
                if (lines == null || lines.length < 2 ||
                    lines[0].trim().isEmpty() || lines[1].trim().isEmpty()) {
                    writeStatus("ERROR empty request");
                    return;
                }
                download(lines[0].trim(), lines[1].trim());
            } catch (Exception e) {
                Log.e("LlmDownloader", "handleRequest failed", e);
                writeStatus("ERROR " + e.getMessage());
            }
        }).start();
    }

    private void download(String urlStr, String destPath) {
        File dest = new File(destPath);
        File part = new File(destPath + ".part");
        HttpURLConnection conn = null;
        try {
            dest.getParentFile().mkdirs();
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                writeStatus("ERROR HTTP " + code);
                return;
            }
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[65536];
                long got = 0;
                int lastPct = -1, n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    got += n;
                    if (total > 0) {
                        int pct = (int) (got * 100 / total);
                        if (pct != lastPct) { writeStatus("DOWNLOADING " + pct); lastPct = pct; }
                    }
                }
            }
            if (part.renameTo(dest)) writeStatus("DONE");
            else writeStatus("ERROR could not finalize file");
        } catch (Exception e) {
            part.delete();
            writeStatus("ERROR " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String[] readLines(File f) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String a = r.readLine();
            String b = r.readLine();
            return new String[]{ a == null ? "" : a, b == null ? "" : b };
        } catch (Exception e) {
            return null;
        }
    }

    private void writeStatus(String s) {
        try (FileOutputStream out = new FileOutputStream(STATUS)) {
            out.write(s.getBytes());
        } catch (Exception e) {
            // best effort
        }
    }
}
