package io.github.jordanwoodson.extension.shared.kick;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * Touches on Kick's player. Kick's player controls are React Native views over the native player view, so the
 * touch is taken where it enters the app, in MainActivity.dispatchTouchEvent, and only when it starts on the
 * player view.
 *
 * Player gestures: in landscape, a vertical swipe on the left of the video changes the window's brightness, on
 * the right the media volume. Kick's own taps and its swipes out of fullscreen (in the middle of the video) are left
 * alone: a touch only becomes a swipe once it moves vertically past the touch slop, and then the rest of the app
 * gets a cancel.
 */
@SuppressWarnings("unused")
public final class KickTouch {
    static final String GESTURES = "player_gestures";

    private static final int IDLE = 0;
    private static final int CANDIDATE = 1;
    private static final int SWIPE = 2;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static int state = IDLE;
    private static int pointer;
    private static float downX;
    private static float downY;
    private static float height;
    private static float start;
    private static boolean swipe;
    private static boolean brightness;
    private static boolean brightnessSet;
    private static WeakReference<TextView> indicator = new WeakReference<>(null);
    private static final Runnable HIDE = KickTouch::hideIndicator;

    private KickTouch() {
    }

    /**
     * Injection point: the start of the dispatchTouchEvent the patch adds to MainActivity, with Kick's player view
     * class (KickIvsPlayerV2View). True consumes the event; false lets the activity dispatch it as usual.
     */
    public static boolean dispatch(Activity activity, MotionEvent event, Class<?> playerView) {
        if (activity == null || event == null || playerView == null) return false;
        try {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    state = IDLE;
                    resetBrightness(activity);
                    if (begin(activity, event, playerView)) state = CANDIDATE;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (state == CANDIDATE) return move(activity, event);
                    if (state == SWIPE) {
                        int index = event.findPointerIndex(pointer);
                        if (index >= 0) apply(activity, (downY - event.getY(index)) / height);
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean claimed = state == SWIPE;
                    state = IDLE;
                    if (claimed) {
                        MAIN.removeCallbacks(HIDE);
                        MAIN.postDelayed(HIDE, 700);
                    }
                    return claimed;
                default:
                    // A second finger: leave pinch to zoom and the rest to Kick.
                    if (state == SWIPE) {
                        state = IDLE;
                        hideIndicator();
                        return true;
                    }
                    state = IDLE;
                    return false;
            }
        } catch (Throwable t) {
            state = IDLE;
            Logger.d("player touch: " + t);
            return false;
        }
    }

    private static boolean on(String key) {
        return Settings.isRegistered(key) && Settings.isEnabled(key);
    }

    /** Whether a touch here may become one of Lurk's gestures: it starts on the player, away from its edges. */
    private static boolean begin(Activity activity, MotionEvent event, Class<?> type) {
        boolean gestures = on(GESTURES);
        if (!gestures) return false;
        if (event.getPointerCount() != 1 || activity.isInPictureInPictureMode()) return false;
        AccessibilityManager accessibility = (AccessibilityManager) activity.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (accessibility != null && accessibility.isTouchExplorationEnabled()) return false;
        View decor = activity.getWindow().getDecorView();
        float x = event.getRawX();
        float y = event.getRawY();
        Rect rect = playerAt(decor, type, (int) x, (int) y, null);
        if (rect == null) return false;
        // Leave the top edge (system bars, the minimize and settings buttons) and the seek bar at the bottom alone.
        if (y < rect.top + rect.height() * 0.15f || y > rect.bottom - rect.height() * 0.2f) return false;
        float relative = (x - rect.left) / rect.width();
        // Swipes: landscape, a player at least half the window wide, its left or right side.
        swipe = gestures && rect.width() >= decor.getWidth() / 2 && (relative <= 0.35f || relative >= 0.65f)
                && activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (!swipe) return false;
        pointer = event.getPointerId(0);
        downX = event.getX();
        downY = event.getY();
        height = Math.max(1, rect.height());
        brightness = relative <= 0.35f;
        return true;
    }

    private static boolean move(Activity activity, MotionEvent event) {
        int index = event.findPointerIndex(pointer);
        if (index < 0 || event.getPointerCount() > 1) {
            state = IDLE;
            return false;
        }
        float dx = event.getX(index) - downX;
        float dy = event.getY(index) - downY;
        int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        if (swipe && Math.abs(dy) > slop && Math.abs(dy) > 2 * Math.abs(dx)) {
            if (!claim(activity)) {
                state = IDLE;
                return false;
            }
            state = SWIPE;
            downY = event.getY(index);
            // The rest of the app saw the touch go down; it gets a cancel instead of the swipe.
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            activity.getWindow().superDispatchTouchEvent(cancel);
            cancel.recycle();
            return true;
        }
        if (Math.abs(dx) > slop || Math.abs(dy) > slop) state = IDLE;
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Player gestures: brightness and volume

    private static boolean claim(Activity activity) {
        if (brightness) {
            float current = activity.getWindow().getAttributes().screenBrightness;
            if (current < 0) {
                try {
                    current = android.provider.Settings.System.getInt(activity.getContentResolver(),
                            android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f;
                } catch (android.provider.Settings.SettingNotFoundException e) {
                    current = 0.5f;
                }
            }
            start = current;
        } else {
            AudioManager audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) return false;
            start = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        }
        return true;
    }

    private static void apply(Activity activity, float fraction) {
        String text;
        if (brightness) {
            Window window = activity.getWindow();
            float value = Math.max(0.01f, Math.min(1f, start + fraction));
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.screenBrightness = value;
            window.setAttributes(attributes);
            brightnessSet = true;
            text = "Brightness " + Math.round(value * 100) + "%";
        } else {
            AudioManager audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) return;
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int value = Math.max(0, Math.min(max, Math.round(start + fraction * max)));
            if (value != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
            }
            text = "Volume " + Math.round(value * 100f / Math.max(1, max)) + "%";
        }
        showIndicator(activity, text);
    }

    /** Back out of landscape, the window's brightness goes back to the system's, if a swipe changed it. */
    private static void resetBrightness(Activity activity) {
        if (!brightnessSet) return;
        if (activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) return;
        brightnessSet = false;
        Window window = activity.getWindow();
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        window.setAttributes(attributes);
    }

    private static void showIndicator(Activity activity, String text) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        TextView view = indicator.get();
        if (view == null || view.getParent() != decor) {
            if (view != null && view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
            float density = activity.getResources().getDisplayMetrics().density;
            view = new TextView(activity);
            view.setTextColor(Color.WHITE);
            view.setTextSize(16);
            int padding = Math.round(12 * density);
            view.setPadding(padding * 2, padding, padding * 2, padding);
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xB3000000);
            background.setCornerRadius(12 * density);
            view.setBackground(background);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            decor.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            indicator = new WeakReference<>(view);
        }
        view.setText(text);
        view.setVisibility(View.VISIBLE);
        MAIN.removeCallbacks(HIDE);
    }

    private static void hideIndicator() {
        TextView view = indicator.get();
        if (view != null && view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        indicator = new WeakReference<>(null);
    }

    // ---------------------------------------------------------------------------------------------
    // The player under a touch

    /**
     * The on-screen bounds of the largest shown player view (an instance of {@code type}) that contains the
     * point, or null. When {@code found} is given, its first element receives that view.
     */
    static Rect playerAt(View root, Class<?> type, int x, int y, View[] found) {
        View[] best = new View[1];
        Rect[] bounds = new Rect[1];
        search(root, type, x, y, best, bounds);
        if (found != null) found[0] = best[0];
        return bounds[0];
    }

    private static void search(View view, Class<?> type, int x, int y, View[] best, Rect[] bounds) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (type.isInstance(view)) {
            if (view.getWidth() <= 0 || view.getHeight() <= 0 || !view.isShown()) return;
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            Rect rect = new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
            if (rect.contains(x, y) && (bounds[0] == null
                    || (long) rect.width() * rect.height() > (long) bounds[0].width() * bounds[0].height())) {
                best[0] = view;
                bounds[0] = rect;
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) search(group.getChildAt(i), type, x, y, best, bounds);
        }
    }
}
