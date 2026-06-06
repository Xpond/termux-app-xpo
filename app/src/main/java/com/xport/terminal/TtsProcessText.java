package com.xport.terminal;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * "Speak" in the text-selection menu. Selecting text in any app — Chrome and
 * WebViews included — and tapping Speak lands here via PROCESS_TEXT.
 *
 * This is the reliable path for selected text: a browser renders its page in one
 * virtual view and never exposes the selection to the accessibility tree (so the
 * floating button's screen-walk can't see it), but the system hands the
 * highlighted string straight to a PROCESS_TEXT activity. We forward it to the
 * player and finish at once, so no window ever shows.
 */
public class TtsProcessText extends Activity {

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        CharSequence sel = getIntent().getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if (sel != null) TtsPlayerService.speak(this, sel.toString());
        finish();
    }
}
