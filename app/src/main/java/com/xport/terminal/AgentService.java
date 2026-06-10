package com.xport.terminal;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The phone-control agent loop, as a real tool-calling agent: the model calls
 * tools (click/type/scroll/back/home/wait/open_app/done) via native OpenAI
 * `tool_calls`; we execute each one through {@link TtsAccessibilityService}
 * (eyes/hands) and reply with a `role:"tool"` result — the SETTLED post-action
 * screen, or an explicit "still changing" signal. The model reasons over truth
 * instead of prose we regex (the old parse-a-verb loop caused runaway clicking:
 * the model had no channel to say "stop" or "wait" — see docs/agent.md).
 *
 * The brain is whatever serves an OpenAI-compatible chat API on 127.0.0.1:
 * on-device {@link LlmServerService} (`llmd`), or a full model on a paired PC
 * via `adb reverse` (the default — verified: Ollama gemma4:e4b emits clean
 * tool_calls and a `reasoning` field we log). Port/model/token come from the
 * same `.llmd_*` files `llmd` writes.
 *
 * The model owns completion: `done(summary)` and plain text with no tool call
 * are messages to the user — they YIELD THE TURN, they don't end the session.
 * The conversation stays alive in this service; `.agent_msg` (via
 * {@link AgentControl}) appends a follow-up and resumes the loop, so "now mark
 * it done" works. The step cap is only a backstop. `open_app` launches apps by
 * label, so a goal can start from xport without manually switching apps.
 *
 * Context stays flat no matter how long the chat runs: screens are ~90% of the
 * tokens (~700 each, measured) and only the latest is truth, so before every
 * model call all but the last two are stubbed out. What survives is the goal,
 * the user's corrections, and the action narrative — each step costs ~60 tokens
 * for good.
 */
public class AgentService extends Service {

    private static final String TAG = "AgentService";
    private static final String HOME = "/data/data/com.xport.terminal/files/home";
    private static final String LOG = HOME + "/.agent_log";
    private static final String CONV = HOME + "/.agent_conv";
    private static final String MODEL_FILE = HOME + "/.llmd_model";
    private static final String PORT_FILE = HOME + "/.llmd_port";
    private static final String TOKEN_FILE = HOME + "/.llmd_token";

    static final String EXTRA_GOAL = "goal";
    static final String EXTRA_STOP = "stop";
    static final String EXTRA_MSG = "msg";
    private static final int MAX_STEPS = 20;
    private static final int SETTLE_MS = 800;
    private static final String SCREEN_MARK = "[{\"i\"";   // start of a screen inventory

    private static final String PROMPT =
        "You control the user's Android phone, in a chat with its user. Each turn you see " +
        "the current screen as a JSON list of actionable elements (i = index, role, label). " +
        "Call ONE tool per turn; the tool result is the screen after the action. Pick " +
        "elements by their label. If a result says the screen is still changing, call wait " +
        "before acting. If the goal involves another app, your FIRST action must be open_app " +
        "— never hunt for the app on the home screen. After every action, read the new " +
        "screen and judge whether the goal is now achieved — controls changing (e.g. a Start " +
        "button becoming Pause) means the action WORKED; call done then, never repeat the " +
        "action. When the goal is fully achieved, call done with a one-line summary. " +
        "Replying with plain text (no tool call) shows your text to the user and waits for " +
        "their reply — use it for questions or when you cannot proceed. The user may send " +
        "corrections mid-task; always follow the latest message.";

    private static final String TOOLS = "[" +
        fn("click", "Tap element i", "\"index\":{\"type\":\"integer\"}", "\"index\"") + "," +
        fn("type", "Put text into field i (replaces its content)",
           "\"index\":{\"type\":\"integer\"},\"text\":{\"type\":\"string\"}", "\"index\",\"text\"") + "," +
        fn("scroll", "Scroll element i forward (down)", "\"index\":{\"type\":\"integer\"}", "\"index\"") + "," +
        fn("back", "Press the Back button", "", "") + "," +
        fn("home", "Go to the home screen", "", "") + "," +
        fn("wait", "Do nothing and look at the screen again (it was mid-transition)", "", "") + "," +
        fn("open_app", "Launch an app by its visible name", "\"name\":{\"type\":\"string\"}", "\"name\"") + "," +
        fn("done", "Goal achieved (or impossible): end the run",
           "\"summary\":{\"type\":\"string\"}", "\"summary\"") +
        "]";

    private static String fn(String name, String desc, String props, String req) {
        return "{\"type\":\"function\",\"function\":{\"name\":\"" + name + "\",\"description\":\"" +
               desc + "\",\"parameters\":{\"type\":\"object\",\"properties\":{" + props +
               "},\"required\":[" + req + "]}}}";
    }

    // The session is STATIC: Android destroys an idle started service whenever it
    // likes (verified live — a follow-up minutes after `done` hit a fresh instance
    // and found no conversation). The chat must survive instance churn; it dies
    // only with the process or `agent stop`.
    private static volatile boolean sRunning;
    private static volatile String sPendingMsg;  // user message to inject before the next turn
    private static JSONArray sConv;              // the live conversation; survives between turns
    private String mLastScreen = "[]";           // the inventory the model last saw

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (intent.getBooleanExtra(EXTRA_STOP, false)) {
            sConv = null;
            sPendingMsg = null;
            if (sRunning) { sRunning = false; log("stopped: agent stop"); }
            return START_NOT_STICKY;
        }
        String goal = intent.getStringExtra(EXTRA_GOAL);
        String msg = intent.getStringExtra(EXTRA_MSG);
        if (goal != null && !goal.trim().isEmpty()) {
            if (sRunning) {   // one run at a time
                log("agent: a run is active — type a message instead, or agent stop first");
                return START_NOT_STICKY;
            }
            sConv = null;                            // a goal starts a fresh conversation
            run(goal.trim(), null);
        } else if (msg != null && !msg.trim().isEmpty()) {
            if (sRunning) sPendingMsg = msg.trim();  // inject into the running loop
            else if (sConv != null) run(null, msg.trim());
            else log("agent: no conversation — start one with  agent \"<goal>\"");
        }
        return START_NOT_STICKY;
    }

    private void run(String goal, String msg) {
        sRunning = true;
        new Thread(() -> {
            try { loop(goal, msg); } finally { sRunning = false; }
            // A message typed in the run's final moments lands in sPendingMsg just
            // as the loop exits — without this it would be swallowed silently.
            String missed = sPendingMsg;
            if (missed != null && sConv != null) { sPendingMsg = null; run(null, missed); }
        }, "agent-loop").start();
    }

    /** One turn of the chat: seed (new goal) or extend (follow-up) the conversation,
     *  then loop tool calls until the model speaks to the user — which yields the
     *  turn but keeps the conversation for the next message. */
    private void loop(String goal, String msg) {
        TtsAccessibilityService eyes = TtsAccessibilityService.INSTANCE;
        if (eyes == null) { log("error: accessibility is off — enable it in Settings"); return; }
        JSONArray messages = sConv;
        try {
            if (goal != null) {
                log("goal: " + goal);
                // The open_app instruction rides in the USER message, not just the system
                // prompt: gemma demonstrably ignores system-prompt and tool-description
                // instructions but follows the same words here (verified by replaying
                // real device conversations against Ollama).
                messages = sConv = new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", PROMPT))
                    .put(new JSONObject().put("role", "user")
                        .put("content", "Goal: " + goal +
                            "\n(If this involves another app, call open_app first — never hunt " +
                            "the home screen. If the goal asks a question, answer it in done's " +
                            "summary using what the screen shows.)\n" + observe(eyes, null)));
            } else {
                // Follow-up: the user is typing in xport, so the target app likely left
                // the foreground — tell the model instead of letting it act blind.
                messages.put(new JSONObject().put("role", "user")
                    .put("content", msg + "\n(You may need open_app to bring the app back " +
                        "to the front before acting.)"));
            }
            String lastSig = null, prevSig = null;
            int repeats = 0;
            boolean leftXport = false;
            for (int step = 1; step <= MAX_STEPS && sRunning; step++) {
                prune(messages);
                JSONObject m = ask(messages);
                if (m == null) { log("error: no reply from model"); return; }
                if (!sRunning) { log("stopped: agent stop"); return; }
                // Pause switch: the agent only ever acts OUTSIDE xport, so once the
                // run has left it, xport in the foreground means the user took over.
                // (The last runaway fought the user for the screen — it clicked
                // xport's own keys, then the user's quick-settings.) Checked after
                // ask(), right before acting: the model can think for 30s+ and the
                // user may have grabbed the phone meanwhile. The conversation is
                // kept — typing a message resumes it; that's the chat feedback loop.
                String fg = foreground(eyes);
                if (fg != null && !fg.equals(getPackageName())) leftXport = true;
                else if (leftXport && getPackageName().equals(fg)) {
                    log("paused: you have control — type to continue, or agent stop");
                    return;
                }
                String thinking = m.optString("reasoning", "").trim();
                if (!thinking.isEmpty())
                    log("  thinking: " + (thinking.length() > 300
                        ? thinking.substring(0, 300) + "…" : thinking).replace("\n", " "));
                JSONArray calls = m.optJSONArray("tool_calls");
                String content = m.optString("content", "").trim();
                if (calls == null || calls.length() == 0) {   // plain text = message to the user
                    messages.put(new JSONObject().put("role", "assistant").put("content", content));
                    log("agent: " + (content.isEmpty() ? "(empty reply)" : content));
                    return;   // yield the turn; the conversation waits for the user's reply
                }
                // One action per turn: act, observe, decide again. If the model emitted
                // parallel calls, keep only the first — every recorded call must get
                // exactly one tool result or the next request is malformed.
                JSONObject call = calls.getJSONObject(0);
                JSONObject f = call.getJSONObject("function");
                String name = f.getString("name");
                Object raw = f.opt("arguments");   // spec says string; some servers send an object
                JSONObject args = raw instanceof JSONObject ? (JSONObject) raw
                                : new JSONObject(raw == null ? "{}" : raw.toString());
                log("step " + step + ": " + name + " " + args);
                messages.put(new JSONObject().put("role", "assistant").put("content", content)
                    .put("tool_calls", new JSONArray().put(call)));
                if ("done".equals(name)) {
                    log("done: " + args.optString("summary"));
                    // Answer the call so the kept conversation stays well-formed
                    // (an unanswered tool_call malforms every later request).
                    messages.put(new JSONObject().put("role", "tool")
                        .put("tool_call_id", call.optString("id"))
                        .put("content", "Reported to the user."));
                    return;   // yield the turn
                }
                String result = execute(eyes, name, args);
                // Cycle nudge: repeating a call means it's not progressing. Match one
                // AND two steps back — a real run burned 9 steps on a click-A/click-B
                // oscillation that consecutive-only matching never saw. Tell the model
                // (it owns stop) instead of killing the run in code.
                String sig = name + " " + args;
                repeats = sig.equals(lastSig) || sig.equals(prevSig) ? repeats + 1 : 0;
                prevSig = lastSig;
                lastSig = sig;
                if (repeats >= 2)
                    result += "\nYou are going in circles — \"" + sig + "\" was already tried " +
                              "and the screen came back the same. Do something different, ask " +
                              "the user for help in plain text, or call done explaining the blocker.";
                log("  -> " + (result.length() > 160 ? result.substring(0, 160) + "…" : result)
                              .replace("\n", " "));
                messages.put(new JSONObject().put("role", "tool")
                    .put("tool_call_id", call.optString("id"))
                    .put("content", result));
                // A message typed mid-run lands here, between actions: the user's
                // correction becomes the very next thing the model reads.
                String pending = sPendingMsg;
                if (pending != null) {
                    sPendingMsg = null;
                    messages.put(new JSONObject().put("role", "user").put("content", pending));
                    lastSig = null; prevSig = null; repeats = 0;   // new direction, not a stuck loop
                }
            }
            log("stopped: hit the " + MAX_STEPS + "-step limit — type to continue");
        } catch (Exception e) { log("error: " + e); }
        finally { dump(messages); }
    }

    /** Keep context flat: stub every screen but the last two (current + previous,
     *  so the model can still compare before/after). Screens are ~90% of the
     *  tokens and only the latest is truth — everything that must persist (goal,
     *  user corrections, the action narrative) carries no marker and is untouched.
     *  Idempotent: a stubbed message no longer contains the marker. */
    private static void prune(JSONArray messages) throws Exception {
        int seen = 0;   // walk backwards so "last two" is natural
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject m = messages.getJSONObject(i);
            String c = m.optString("content", "");
            int at = c.indexOf(SCREEN_MARK);
            if (at < 0) continue;
            if (++seen > 2)
                m.put("content", c.substring(0, at) + "(stale screen — superseded by a later one)");
        }
    }

    /** Full conversation (every screen, tool call, and result) to ~/.agent_conv —
     *  the human log truncates results; this is the ground truth for debugging. */
    private void dump(JSONArray messages) {
        try (FileOutputStream out = new FileOutputStream(CONV)) {
            out.write(messages.toString(2).getBytes("UTF-8"));
        } catch (Exception e) { /* best effort */ }
    }

    /** Run one tool; the result is the settled post-action screen. */
    private String execute(TtsAccessibilityService eyes, String name, JSONObject args) {
        switch (name) {
            case "wait":
                sleep(SETTLE_MS);
                return observe(eyes, null);
            case "open_app": {
                String app = args.optString("name");
                String opened = openApp(app);
                if (opened == null) return "No app named \"" + app + "\" found.";
                sleep(SETTLE_MS);
                return "Opened " + opened + ". " + observe(eyes, mLastScreen);
            }
            case "back": case "home":
                eyes.act(name, -1, "");
                sleep(SETTLE_MS);
                return observe(eyes, null);
            case "click": case "type": case "scroll": {
                // Never act on xport itself — it's the user's chat, not a target.
                // (The original runaway clicked xport's own keys; this closes that
                // class for good, including right after a follow-up message when
                // the terminal is still in front.)
                if (getPackageName().equals(foreground(eyes)))
                    return "You are looking at the xport terminal (the user's chat) — don't "
                         + "act here. Call open_app to bring the target app to the front.";
                String before = mLastScreen;
                boolean ok = eyes.act(name, args.optInt("index", -1), args.optString("text", ""));
                if (!ok) return "The action did not dispatch (element may be stale). "
                               + observe(eyes, null);
                sleep(SETTLE_MS);
                // A scroll legitimately may not change the inventory; don't wait for it to.
                return observe(eyes, "scroll".equals(name) ? null : before);
            }
            default:
                return "Unknown tool: " + name;
        }
    }

    /** Snapshot once the screen SETTLES: two consecutive equal non-empty snapshots,
     *  and (after a click/type) different from the screen acted on — the a11y tree
     *  updates in stages behind the visual UI, and a half-built frame is what used
     *  to cause runaway actions. If it never settles, say so honestly: the model
     *  has `wait` to look again. */
    private String observe(TtsAccessibilityService eyes, String acted) {
        String inv = "[]", prev = null;
        for (int t = 0; t < 6; t++) {
            if (t > 0) sleep(600);
            inv = eyes.snapshot();
            boolean changed = acted == null || !inv.equals(acted);
            boolean stable  = !"[]".equals(inv) && inv.equals(prev);
            if (changed && stable) { mLastScreen = inv; return "Screen: " + inv; }
            prev = inv;
        }
        mLastScreen = inv;
        if ("[]".equals(inv)) return "Screen is empty (nothing actionable yet). Call wait to re-check.";
        return "Screen (still changing — call wait if it looks incomplete): " + inv;
    }

    /** Package of the app the agent is looking at, or null if unreadable. */
    private static String foreground(TtsAccessibilityService eyes) {
        try {
            android.view.accessibility.AccessibilityNodeInfo r = eyes.getRootInActiveWindow();
            return r == null || r.getPackageName() == null ? null : r.getPackageName().toString();
        } catch (Exception e) { return null; }
    }

    /** Launch an app by visible label (exact match wins, else contains). Works from
     *  a background service because the app holds the overlay permission. */
    private String openApp(String name) {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        String want = name.trim().toLowerCase();
        String pkg = null, label = null;
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String l = String.valueOf(ri.loadLabel(pm));
            if (l.toLowerCase().equals(want)) { pkg = ri.activityInfo.packageName; label = l; break; }
            if (pkg == null && l.toLowerCase().contains(want)) {
                pkg = ri.activityInfo.packageName; label = l;
            }
        }
        Intent launch = pkg == null ? null : pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return null;
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return label;
    }

    // ---- decide ---------------------------------------------------------------

    /** One model turn: returns choices[0].message, or null on failure. Retries once
     *  after a pause (cold model load races the first request). */
    private JSONObject ask(JSONArray messages) throws Exception {
        JSONObject body = new JSONObject();
        String model = read(MODEL_FILE, "");
        if (!model.isEmpty()) body.put("model", model);
        body.put("messages", messages);
        body.put("tools", new JSONArray(TOOLS));
        // Generous: thinking models reason before the call, and elaborate goals
        // mean long reasoning — a tight cap returned empty replies (all tokens
        // burned thinking, finish_reason "length"). The call itself is tiny.
        body.put("max_tokens", 4096);
        body.put("temperature", 0);
        String b = body.toString();
        JSONObject m = post(b);
        if (m == null) { sleep(3000); m = post(b); }
        return m;
    }

    private JSONObject post(String body) {
        String port = read(PORT_FILE, "8080");
        String token = read(TOKEN_FILE, "");
        HttpURLConnection c = null;
        try {
            URL url = new URL("http://127.0.0.1:" + port + "/v1/chat/completions");
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            c.setDoOutput(true);
            c.setConnectTimeout(10000);
            c.setReadTimeout(60000);
            try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes("UTF-8")); }
            int code = c.getResponseCode();
            if (code != 200) { log("  http " + code); return null; }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String ln; while ((ln = br.readLine()) != null) sb.append(ln);
            }
            JSONObject choice = new JSONObject(sb.toString()).getJSONArray("choices").getJSONObject(0);
            String fr = choice.optString("finish_reason", "");
            if (!"stop".equals(fr) && !"tool_calls".equals(fr)) log("  finish_reason: " + fr);
            return choice.getJSONObject("message");
        } catch (Exception e) {
            log("  ask failed: " + e);
            return null;
        } finally { if (c != null) c.disconnect(); }
    }

    // ---- io ---------------------------------------------------------------------

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String read(String path, String dflt) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String l = r.readLine();
            return l == null || l.trim().isEmpty() ? dflt : l.trim();
        } catch (Exception e) { return dflt; }
    }

    private void log(String line) {
        Log.i(TAG, line);
        try (FileOutputStream out = new FileOutputStream(LOG, true)) {
            out.write((line + "\n").getBytes());
        } catch (Exception e) { /* best effort */ }
    }
}
