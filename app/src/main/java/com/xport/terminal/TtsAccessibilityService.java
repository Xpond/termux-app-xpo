package com.xport.terminal;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the text on the current screen aloud.
 *
 * The only frontend: it can see any app's on-screen content (that's what an
 * accessibility service is for), so "point at a webpage and listen" needs no
 * cooperation from the other app. {@link #readScreen()} collects the visible
 * body text and feeds it to {@link TtsPlayerService}, then stops.
 *
 * It reads body text only — buttons and other clickable controls are skipped, so
 * nav chrome ("Home", "Menu", "Share") isn't spoken. The user must enable it
 * once: Settings > Accessibility > XPort. We don't react to accessibility
 * *events* (would be a firehose); the floating button calls {@link #readScreen()}
 * on demand via {@link #INSTANCE}.
 */
public class TtsAccessibilityService extends AccessibilityService {

    static TtsAccessibilityService INSTANCE;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile boolean mReading;
    private volatile boolean mPaused;

    @Override public void onServiceConnected() {
        INSTANCE = this;
        mMain.post(TtsFloatingButton::showButton); // a11y just enabled — show the button
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* on-demand only */ }
    @Override public void onInterrupt() {}

    @Override
    public boolean onUnbind(Intent intent) {
        // Accessibility was turned off — stop any reading and hide the button,
        // which is meaningless without us (it has nothing to read the screen with).
        if (mReading) stopReading();
        mMain.post(TtsFloatingButton::hideButton);
        if (INSTANCE == this) INSTANCE = null;
        return super.onUnbind(intent);
    }

    /** Arm a read: mark us reading and install the idle callback that flips the
     *  button back to ▶ when the player finishes. Shared by the screen-walk and
     *  the clipboard path (which starts the player from {@link TtsReadClipboard})
     *  so the button reflects playback state no matter how reading was kicked off. */
    public void beginRead() {
        mReading = true;
        mPaused = false;
        TtsPlayerService.sOnIdle = () -> mMain.post(() -> {
            mReading = false;
            mPaused = false;
            TtsFloatingButton.resetIcon();
        });
    }

    /** Pause playback, keeping position; the button flips to ▶. No-op if idle. */
    public void pauseReading() {
        if (!mReading || mPaused) return;
        mPaused = true;
        TtsPlayerService.pause(this);
    }

    /** Resume from where we paused; the button flips to ⏸. No-op if not paused. */
    public void resumeReading() {
        if (!mReading || !mPaused) return;
        mPaused = false;
        TtsPlayerService.resume(this);
    }

    /** Whether reading is paused (vs. actively playing). Drives the button icon. */
    public boolean isPaused() { return mPaused; }

    /** Read the current screen's body text, then stop. */
    public void readScreen() {
        beginRead();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Rect screen = new Rect(0, 0, dm.widthPixels, dm.heightPixels);
        // Walk the tree off the UI thread: getRootInActiveWindow() + the recursive
        // collect() are cross-process IPC per node, and on heavy screens (a PDF
        // page, a long Reddit thread) that takes seconds — run on the main thread
        // it freezes the tap and the whole app. The button returns instantly;
        // reading just starts a beat later.
        new Thread(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { abort(); return; }
            List<String> sentences = new ArrayList<>();
            collect(root, screen, sentences, false);

            // Join fragments with newlines (espeak treats a newline as a clause
            // break -> a natural pause, never voiced). Add a period only to a
            // fragment that doesn't already end in sentence punctuation, so we keep
            // end-of-sentence prosody without the spurious periods that made it say
            // "dot".
            StringBuilder sb = new StringBuilder();
            for (String s : sentences) {
                sb.append(s);
                char last = s.charAt(s.length() - 1);
                if (last != '.' && last != '!' && last != '?' && last != ':') sb.append('.');
                sb.append('\n');
            }
            String text = sb.toString().trim();
            if (text.isEmpty()) { abort(); return; }
            TtsPlayerService.speak(this, text);
        }, "tts-read-screen").start();
    }

    /** Reading found nothing to say (no tree / no body text): reset state and the
     *  button icon so it doesn't stay stuck on ■. The off-thread walk can't touch
     *  the icon directly, so post to main (same as the idle callback). */
    private void abort() {
        mReading = false;
        mMain.post(TtsFloatingButton::resetIcon);
    }

    /** Whether a read-aloud session is currently active (drives the button icon). */
    public boolean isReading() { return mReading; }

    /** Stop any in-progress reading. */
    public void stopReading() {
        mReading = false;
        mPaused = false;
        TtsPlayerService.sOnIdle = null;
        startService(new Intent(this, TtsPlayerService.class)
            .setAction(TtsPlayerService.ACTION_STOP));
    }

    /**
     * DFS the node tree, collecting only the body text the user can actually see.
     * Filters, each killing a class of garbage seen in testing:
     *  - skip nodes the framework marks not-visible-to-user (collapsed/hidden);
     *  - skip nodes whose on-screen bounds fall outside the display;
     *  - skip text inside *chrome* — a real button/switch, or a clickable node only
     *    one line tall (a nav item, a chip). We do NOT skip all clickable subtrees:
     *    card-based apps (news feeds, Reddit) wrap whole articles in a clickable
     *    card, so blanket-skipping clickables threw away the entire screen. A tall
     *    clickable node is content, not chrome — see {@link #isChrome};
     *  - take a node's own text only if no descendant carries text, so we read
     *    leaf content once instead of a container plus all its children.
     */
    private void collect(AccessibilityNodeInfo node, Rect screen, List<String> out,
                         boolean inControl) {
        if (node == null || !node.isVisibleToUser()) return;
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        if (b.isEmpty() || !Rect.intersects(b, screen)) return;

        inControl = inControl || isChrome(node, b);

        boolean childHasText = false;
        for (int i = 0; i < node.getChildCount(); i++) {
            int before = out.size();
            collect(node.getChild(i), screen, out, inControl);
            if (out.size() > before) childHasText = true;
        }
        if (!childHasText && !inControl) {
            // Prefer getText(); fall back to contentDescription. Canvas-rendered UIs
            // (Flutter, Compose, WebView) expose no real TextViews — the whole
            // screen is generic View nodes with empty text and the actual content
            // in contentDescription. Without this fallback those apps read nothing.
            CharSequence t = node.getText();
            if (t == null || t.toString().trim().isEmpty()) t = node.getContentDescription();
            if (t != null && t.toString().trim().length() > 0) out.add(t.toString().trim());
        }
    }

    /**
     * Is this node nav chrome (skip its text) vs. body content?
     *  - a Button/Switch/CheckBox class is always chrome;
     *  - a clickable node is chrome only if it's short — roughly one line tall.
     *    Toolbar items, tabs and chips are one line; a tappable article card is
     *    many lines. Height (in dp) is the cheap, app-agnostic discriminator that
     *    keeps card content while still dropping nav.
     */
    private boolean isChrome(AccessibilityNodeInfo n, Rect bounds) {
        CharSequence cls = n.getClassName();
        if (cls != null) {
            String c = cls.toString();
            if (c.contains("Button") || c.contains("Switch") || c.contains("CheckBox")) return true;
        }
        if (!n.isClickable()) return false;
        float density = getResources().getDisplayMetrics().density;
        return bounds.height() < 56 * density; // ~one line / a touch target
    }
}
