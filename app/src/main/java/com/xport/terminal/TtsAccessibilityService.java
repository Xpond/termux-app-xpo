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

    /** Read the current screen's body text, then stop. */
    public void readScreen() {
        mReading = true;
        // When the speaker goes idle (finished reading), reset state + the button.
        TtsPlayerService.sOnIdle = () -> mMain.post(() -> {
            mReading = false;
            TtsFloatingButton.resetIcon();
        });
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) { mReading = false; return; }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Rect screen = new Rect(0, 0, dm.widthPixels, dm.heightPixels);
        List<String> sentences = new ArrayList<>();
        collect(root, screen, sentences, false);

        // Join fragments with newlines (espeak treats a newline as a clause break
        // -> a natural pause, never voiced). Add a period only to a fragment that
        // doesn't already end in sentence punctuation, so we keep end-of-sentence
        // prosody without the spurious periods that made it say "dot".
        StringBuilder sb = new StringBuilder();
        for (String s : sentences) {
            sb.append(s);
            char last = s.charAt(s.length() - 1);
            if (last != '.' && last != '!' && last != '?' && last != ':') sb.append('.');
            sb.append('\n');
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) { mReading = false; return; }
        startService(new Intent(this, TtsPlayerService.class)
            .setAction(TtsPlayerService.ACTION_SPEAK)
            .putExtra(TtsPlayerService.EXTRA_TEXT, text));
    }

    /** Whether a read-aloud session is currently active (drives the button icon). */
    public boolean isReading() { return mReading; }

    /** Stop any in-progress reading. */
    public void stopReading() {
        mReading = false;
        TtsPlayerService.sOnIdle = null;
        startService(new Intent(this, TtsPlayerService.class)
            .setAction(TtsPlayerService.ACTION_STOP));
    }

    /**
     * DFS the node tree, collecting only the body text the user can actually see.
     * Filters, each killing a class of garbage seen in testing:
     *  - skip nodes the framework marks not-visible-to-user (collapsed/hidden);
     *  - skip nodes whose on-screen bounds fall outside the display;
     *  - skip any text inside a clickable container (buttons / nav chrome). Button
     *    labels usually sit on a non-clickable TextView whose *ancestor* is the
     *    clickable control, so we carry the "inside a control" state down the tree
     *    rather than checking the text node alone;
     *  - take a node's own text only if no descendant carries text, so we read
     *    leaf content once instead of a container plus all its children.
     */
    private void collect(AccessibilityNodeInfo node, Rect screen, List<String> out,
                         boolean inControl) {
        if (node == null || !node.isVisibleToUser()) return;
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        if (b.isEmpty() || !Rect.intersects(b, screen)) return;

        inControl = inControl || isControl(node);

        boolean childHasText = false;
        for (int i = 0; i < node.getChildCount(); i++) {
            int before = out.size();
            collect(node.getChild(i), screen, out, inControl);
            if (out.size() > before) childHasText = true;
        }
        if (!childHasText && !inControl) {
            CharSequence t = node.getText();
            if (t != null && t.toString().trim().length() > 0) out.add(t.toString().trim());
        }
    }

    /** A tappable control or button — its text is nav chrome, not body content. */
    private static boolean isControl(AccessibilityNodeInfo n) {
        if (n.isClickable()) return true;
        CharSequence cls = n.getClassName();
        return cls != null && cls.toString().contains("Button");
    }
}
