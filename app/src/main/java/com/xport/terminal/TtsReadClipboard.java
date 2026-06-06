package com.xport.terminal;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Reads the clipboard aloud — the skin-proof fallback for native apps whose
 * trimmed selection toolbar won't show our "Speak" item ({@link TtsProcessText}).
 * The user copies text, then long-presses the floating button, which launches
 * this. Flow: select -> Copy -> long-press -> hear it.
 *
 * Why an activity at all? Android 10+ blocks clipboard reads from anything
 * without window focus — a background/overlay/accessibility context gets null.
 * A launched activity briefly holds focus, so it can read legitimately. Focus
 * isn't granted until after onCreate, so we read in {@link #onWindowFocusChanged}
 * (reading in onCreate would return null). Transparent, not Theme.NoDisplay:
 * NoDisplay crashes if you finish() after focus, which is exactly what we do.
 */
public class TtsReadClipboard extends Activity {

    private boolean mDone;

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || mDone) return;
        mDone = true; // focus can toggle; read once

        CharSequence text = clipboardText();
        if (text != null) {
            TtsPlayerService.speak(this, text.toString());
        } else {
            // No playback will start, so the player's idle callback won't fire —
            // reset the button here or it stays stuck on ■.
            TtsFloatingButton.resetIcon();
            Toast.makeText(this, "Clipboard is empty — copy some text first",
                Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    /** The clipboard's text, or null if empty / not text. */
    private CharSequence clipboardText() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return null;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence t = clip.getItemAt(0).coerceToText(this);
        return (t != null && t.toString().trim().length() > 0) ? t : null;
    }
}
