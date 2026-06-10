package com.xport.terminal;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The agent's eyes and hands over the accessibility tree. Two operations:
 *
 *  - {@link #snapshot} walks the active window and returns a compact JSON list of
 *    the elements the agent can act on, indexed 1..N. Two hard-won rules:
 *      (1) A label attaches only to a clickable/editable target — the labelled
 *          node is often not the one that accepts a tap, so we index its nearest
 *          clickable/editable ancestor. A *scrollable* ancestor never adopts a
 *          label (a whole-screen ScrollView would otherwise become a bogus
 *          screen-sized element that steals a child's title).
 *      (2) Indices are 1-based: the model counts items 1,2,3… in prose, so a
 *          0-based i makes it emit "click 4" for the 5th element.
 *    Blank-label elements are dropped (the model can only pick by label). Targets
 *    are cached so {@link #act} resolves "click N" to the node the model saw.
 *
 *  - {@link #act} performs one action: click / type / scroll via performAction,
 *    falling back to a real gesture tap at the node's center when performAction
 *    returns false (canvas UIs, or a node that reports clickable but won't honor
 *    ACTION_CLICK). back / home are global actions.
 *
 * Kept out of {@link TtsAccessibilityService} so read-aloud stays self-contained;
 * the service just forwards to these statics, passing itself for the IPC calls.
 */
final class AgentEyes {
    private AgentEyes() {}

    /** Action targets from the last snapshot, in 1-based model order (element i
     *  is sNodes.get(i-1)). */
    private static List<AccessibilityNodeInfo> sNodes = new ArrayList<>();

    /** Walk the active window into a compact actionable inventory (JSON string).
     *  Must run off the UI thread — getRootInActiveWindow + the recursive walk are
     *  cross-process IPC per node. */
    static String snapshot(AccessibilityService svc) {
        sNodes = new ArrayList<>();
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return "[]";
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collect(root, new ArrayList<>(), out, seen);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(out.get(i).replace("INDEX", Integer.toString(i + 1)));  // 1-based
        }
        return sb.append(']').toString();
    }

    /** DFS. `chain` is the ancestor stack to this node (for ancestor resolution). */
    private static void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> chain,
                                List<String> out, Set<String> seen) {
        if (node == null || !node.isVisibleToUser()) return;
        Rect b = new Rect();
        node.getBoundsInScreen(b);
        if (b.isEmpty()) return;

        boolean clickable  = node.isClickable();
        boolean scrollable = node.isScrollable();
        boolean editable   = isEditable(node);
        String label = label(node);

        chain.add(node);
        // Clickable/editable node, or a labelled node borrowing its nearest
        // clickable/editable ancestor -> a click/field target carrying the label.
        if (clickable || editable || !label.isEmpty()) {
            AccessibilityNodeInfo target = clickableTarget(chain);
            if (target != null) {
                String lab = label.isEmpty() ? labelUp(chain) : label;
                // A blank-label EDITABLE field is still actionable — the model
                // must be able to type into it (an empty input has no text/hint,
                // e.g. a task-title box). Give it a placeholder so it survives the
                // blank-drop in emit(). Blank-label buttons stay dropped: unpickable.
                if (lab.isEmpty() && isEditable(target)) lab = "(text field)";
                emit(target, lab, out, seen);
            }
        }
        // A scrollable node is a scroll target on its OWN merit, with its own
        // label only — never a descendant's (rule 1 above).
        if (scrollable && !label.isEmpty()) emit(node, label, out, seen);

        for (int i = 0; i < node.getChildCount(); i++)
            collect(node.getChild(i), chain, out, seen);
        chain.remove(chain.size() - 1);
    }

    /** Add an element for `target` with `label`, deduped by bounds+label. The
     *  INDEX placeholder is filled with the 1-based position in {@link #snapshot}. */
    private static void emit(AccessibilityNodeInfo target, String label,
                             List<String> out, Set<String> seen) {
        if (label.isEmpty()) return;
        Rect tb = new Rect();
        target.getBoundsInScreen(tb);
        if (!seen.add(tb.flattenToString() + ' ' + label)) return;
        sNodes.add(target);
        out.add("{\"i\":INDEX,\"role\":\"" + role(target) + "\",\"label\":\"" + esc(label) + "\"}");
    }

    /** Nearest clickable/editable node up the chain (NOT scrollable), or null. */
    private static AccessibilityNodeInfo clickableTarget(List<AccessibilityNodeInfo> chain) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            AccessibilityNodeInfo n = chain.get(i);
            if (n.isClickable() || isEditable(n)) return n;
        }
        return null;
    }

    /** First non-empty label walking up the chain (for a target with no own text). */
    private static String labelUp(List<AccessibilityNodeInfo> chain) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            String l = label(chain.get(i));
            if (!l.isEmpty()) return l;
        }
        return "";
    }

    private static String role(AccessibilityNodeInfo n) {
        if (isEditable(n))     return "field";
        if (n.isScrollable())  return "scroll";
        return "button";
    }

    private static boolean isEditable(AccessibilityNodeInfo n) {
        if (n.isEditable()) return true;
        CharSequence c = n.getClassName();
        return c != null && (c.toString().endsWith("EditText")
                          || c.toString().endsWith("AutoCompleteTextView"));
    }

    /** Label = text, else contentDescription, else hint (API 26+). */
    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.toString().trim().isEmpty()) t = n.getContentDescription();
        if ((t == null || t.toString().trim().isEmpty()) && Build.VERSION.SDK_INT >= 26)
            t = n.getHintText();
        return t == null ? "" : t.toString().trim();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    // ---- act ----------------------------------------------------------------

    /** Perform one action on a 1-based snapshot index. Returns true if it dispatched. */
    static boolean act(AccessibilityService svc, String verb, int index, String text) {
        if ("back".equals(verb)) return svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        if ("home".equals(verb)) return svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        int idx = index - 1;                          // model indices are 1-based
        if (idx < 0 || idx >= sNodes.size()) return false;
        AccessibilityNodeInfo n = sNodes.get(idx);
        switch (verb) {
            case "type":   return type(n, text);
            // No tap fallback for scroll: a failed scroll on a non-scrollable element
            // must fail honestly — the fallback once turned it into a blind tap that
            // opened a random app.
            case "scroll": return n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            case "click":  return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                || tap(svc, n);
            default:       return false;
        }
    }

    private static boolean type(AccessibilityNodeInfo n, String text) {
        // Focus the field first — ACTION_SET_TEXT on an unfocused field is often
        // dropped. ACTION_FOCUS / ACTION_CLICK are best-effort.
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    /** Real touch at the node's center — for nodes that won't honor performAction.
     *  Needs android:canPerformGestures in the a11y config (API 24+). */
    private static boolean tap(AccessibilityService svc, AccessibilityNodeInfo n) {
        if (Build.VERSION.SDK_INT < 24) return false;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        if (b.isEmpty()) return false;
        Path p = new Path();
        p.moveTo(b.centerX(), b.centerY());
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 50)).build();
        return svc.dispatchGesture(g, null, null);
    }
}
