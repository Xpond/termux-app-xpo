package com.xport.terminal;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The read-aloud trigger: a draggable always-on-top ▶ bubble.
 *
 * Tap -> the accessibility service reads the current screen. Tap while reading
 * -> stop. Drag to reposition. Shown only while the accessibility service is
 * connected (it has nothing to read with otherwise); the service shows/hides it
 * on connect/disconnect.
 *
 * Needs the "Display over other apps" permission (already used by the llmd
 * overlay). No-op without it.
 */
public class TtsFloatingButton {

    private static final String PLAY = "▶", STOP = "■";

    private static TtsFloatingButton sInstance;

    private final Context mContext;
    private WindowManager mWm;
    private TextView mView;

    public TtsFloatingButton(Context context) {
        mContext = context.getApplicationContext();
        sInstance = this;
    }

    /** Reset the icon to ▶ — called when reading finishes on its own. */
    public static void resetIcon() {
        if (sInstance != null && sInstance.mView != null) sInstance.mView.setText(PLAY);
    }

    /** Show the button (called when the accessibility service connects). */
    public static void showButton() { if (sInstance != null) sInstance.show(); }

    /** Hide the button (called when accessibility is turned off). */
    public static void hideButton() { if (sInstance != null) sInstance.hide(); }

    public void show() {
        if (mView != null) return;
        // Only show while accessibility is enabled — the button is useless without
        // it. The service calls showButton() on connect; an activity launch with
        // a11y already on finds INSTANCE set here too.
        if (TtsAccessibilityService.INSTANCE == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(mContext)) return;

        mView = new TextView(mContext);
        mView.setText(PLAY);
        mView.setTextColor(Color.WHITE);
        mView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        mView.setBackgroundColor(0xCC000000);
        int pad = dp(12);
        mView.setPadding(pad, pad, pad, pad);

        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
        // NOT_TOUCH_MODAL is essential: without it the window is touch-modal and
        // receives touches for the WHOLE screen, swallowing taps outside the
        // button (e.g. the keyboard) instead of letting them fall through to the
        // app behind. With it, only touches on the button reach us.
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
          | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(16); lp.y = dp(160);

        mWm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mView.setOnTouchListener(new DragTapListener(lp));
        mWm.addView(mView, lp);
    }

    /** Remove the overlay view. Keeps the singleton so showButton() can re-add it
     *  when accessibility is re-enabled; the activity disposes it in onDestroy. */
    public void hide() {
        if (mView != null && mWm != null) {
            try { mWm.removeView(mView); } catch (Exception ignored) {}
        }
        mView = null;
    }

    private void onTap() {
        TtsAccessibilityService svc = TtsAccessibilityService.INSTANCE;
        if (svc == null) {
            // Accessibility isn't enabled (Android disables it on app update), so
            // there's nothing to read the screen with. Say so instead of silently
            // doing nothing — otherwise the button looks dead.
            Toast.makeText(mContext,
                "Enable xport in Settings > Accessibility to read the screen",
                Toast.LENGTH_LONG).show();
            return;
        }
        // The service owns the real reading state (it ends on its own when the
        // text runs out), so ask it rather than tracking locally.
        if (svc.isReading()) { svc.stopReading(); mView.setText(PLAY); }
        else                 { svc.readScreen(); mView.setText(STOP); }
    }

    /** Distinguishes a tap (toggle) from a drag (reposition). */
    private class DragTapListener implements View.OnTouchListener {
        private final WindowManager.LayoutParams lp;
        private int startX, startY; private float touchX, touchY; private boolean moved;
        DragTapListener(WindowManager.LayoutParams lp) { this.lp = lp; }

        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lp.x; startY = lp.y;
                    touchX = e.getRawX(); touchY = e.getRawY(); moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (e.getRawX() - touchX), dy = (int) (e.getRawY() - touchY);
                    if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) moved = true;
                    lp.x = startX + dx; lp.y = startY + dy;
                    mWm.updateViewLayout(mView, lp);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) onTap();
                    return true;
            }
            return false;
        }
    }

    private int dp(int v) {
        return (int) (v * mContext.getResources().getDisplayMetrics().density);
    }
}
