package com.xport.terminal;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
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
    // Glassy dark fill + a single calm accent for the glyph (shape carries the
    // state, not colour). Diameter is fixed so it reads as a round FAB, not a
    // padded glyph.
    private static final int FILL = 0xCC1C1C1E, ACCENT = 0xFF4DD0E1;
    private static final int SIZE_DP = 44;

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
        mView.setTextColor(ACCENT);
        mView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        mView.setGravity(Gravity.CENTER);
        // Round glassy bubble: dark translucent fill + a hairline light rim for
        // depth (the window is sized to the circle, so an elevation shadow would
        // be clipped — the rim carries the "floating" read instead).
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(FILL);
        bg.setStroke(dp(1), 0x33FFFFFF);
        mView.setBackground(bg);

        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
        // NOT_TOUCH_MODAL is essential: without it the window is touch-modal and
        // receives touches for the WHOLE screen, swallowing taps outside the
        // button (e.g. the keyboard) instead of letting them fall through to the
        // app behind. With it, only touches on the button reach us.
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            dp(SIZE_DP), dp(SIZE_DP),
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

    /** Long-press: read the clipboard. The transparent activity does the actual
     *  read (it needs focus, which an overlay lacks) — see {@link TtsReadClipboard}.
     *  This is the fallback for native apps that hide the "Speak" menu item.
     *
     *  We arm the service so the icon tracks playback (■ now, ▶ on idle) just like
     *  a tap. TtsReadClipboard resets the icon itself if the clipboard is empty
     *  (the player never starts then, so no idle callback fires). */
    private void onLongPress() {
        TtsAccessibilityService svc = TtsAccessibilityService.INSTANCE;
        if (svc != null) { svc.beginRead(); mView.setText(STOP); }
        mContext.startActivity(new Intent(mContext, TtsReadClipboard.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Distinguishes tap (read screen) from drag (reposition) from long-press
     *  (read clipboard). A posted runnable fires the long-press if the finger
     *  neither moves nor lifts within the system timeout. */
    private class DragTapListener implements View.OnTouchListener {
        private final WindowManager.LayoutParams lp;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private int startX, startY; private float touchX, touchY;
        private boolean moved, longPressed;
        private final Runnable longPress = () -> { longPressed = true; onLongPress(); };
        DragTapListener(WindowManager.LayoutParams lp) { this.lp = lp; }

        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lp.x; startY = lp.y;
                    touchX = e.getRawX(); touchY = e.getRawY();
                    moved = false; longPressed = false;
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (e.getRawX() - touchX), dy = (int) (e.getRawY() - touchY);
                    if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) moved = true;
                    if (moved) handler.removeCallbacks(longPress); // it's a drag, not a hold
                    lp.x = startX + dx; lp.y = startY + dy;
                    mWm.updateViewLayout(mView, lp);
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(longPress);
                    if (!moved && !longPressed) onTap();
                    return true;
            }
            return false;
        }
    }

    private int dp(int v) {
        return (int) (v * mContext.getResources().getDisplayMetrics().density);
    }
}
