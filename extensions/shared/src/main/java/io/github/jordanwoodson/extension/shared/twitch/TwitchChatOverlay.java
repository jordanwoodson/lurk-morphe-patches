package io.github.jordanwoodson.extension.shared.twitch;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * "Chat overlay": in landscape, live chat messages scroll right to left across the top of the video
 * (danmaku). Native live chat feeds it each message as it is added to the chat list. The overlay is a
 * plain view above the activity's content that never takes touches or focus; it exists only while the
 * activity is in landscape with a large video showing, and removes itself in portrait, when the video goes
 * away, when chat stops, or when the switch is turned off.
 */
@SuppressWarnings({"unused", "rawtypes"})
public final class TwitchChatOverlay {
    static final String KEY = "chat_overlay";
    static final String KEY_SPEED = "chat_overlay_speed";
    static final String KEY_LINES = "chat_overlay_lines";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_TEXT = 120;
    /** Token class -> its text field, or NO_FIELD when it has none (cheers). */
    private static final ConcurrentHashMap<Class<?>, Field> TEXT_FIELDS = new ConcurrentHashMap<>();
    private static final Field NO_FIELD;
    private static volatile Field nameField;
    private static volatile Class<?> nameClass;
    /** The attached overlay. Main thread only. */
    private static Overlay overlay;
    private static final Map<Object,Owner> OWNERS = new WeakHashMap<>();
    private static final class Owner {
        String room = "";
        long generation;
        boolean authoritative, retired;
    }
    private static boolean loggedFailure;

    static {
        Field none = null;
        try {
            none = TwitchChatOverlay.class.getDeclaredField("loggedFailure");
        } catch (NoSuchFieldException ignored) {
            // Never: the field above exists.
        }
        NO_FIELD = none;
    }

    private TwitchChatOverlay() {
    }

    /**
     * Injection point (native live chat): a live message about to be added to the chat list, with the chat's
     * context, the message's sender and its tokens.
     */
    public static void onMessage(Context context, Object sender, List tokens) {
        onMessage(context, sender, tokens, null);
    }

    /** Destination room-id owns the overlay; source-room-id only identifies a shared-chat sender. */
    public static void onMessage(Context context, Object sender, List tokens, Map tags) {
        onMessage(context, sender, tokens, tags, context);
    }

    public static void onMessage(Context context, Object sender, List tokens, Map tags, Object feeder) {
        try {
            if (!Settings.isEnabled(KEY)) {
                MAIN.post(() -> { if (!Settings.isEnabled(KEY)) detach(); });
                return;
            }
            Activity activity = activity(context);
            if (activity == null || tokens == null) return;
            Object tagged = tags == null ? null : tags.get("room-id");
            String room = tagged instanceof String && ((String) tagged).matches("[0-9]{1,12}") ? (String) tagged : "";
            final Owner owner;
            final long generation;
            synchronized (OWNERS) {
                owner = OWNERS.computeIfAbsent(feeder, key -> new Owner());
                if (owner.retired || owner.authoritative && !room.equals(owner.room)) return;
                if (!room.equals(owner.room)) { owner.room = room; owner.generation++; }
                generation = owner.generation;
            }
            if (!isLandscape(activity)) {
                detach(owner);
                return;
            }
            if (TwitchChatKeywords.hiddenLive(sender, tokens)) return;
            String text = text(tokens);
            if (text.isEmpty()) return;
            String name = name(sender);
            MAIN.post(() -> {
                synchronized (OWNERS) { if (owner.retired || generation != owner.generation) return; }
                show(activity, name, text, owner);
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!loggedFailure) {
                loggedFailure = true;
                Logger.d("chat overlay: failed: " + e);
            }
        }
    }

    /** The measured viewer stream-model observer supplies the destination before chat arrives. */
    public static void channel(Object feeder, Object channelId) {
        if (feeder == null) return;
        String room = channelId == null ? "" : channelId.toString();
        if (!room.isEmpty() && !room.matches("[0-9]{1,12}")) return;
        synchronized (OWNERS) {
            Owner owner = OWNERS.computeIfAbsent(feeder, key -> new Owner());
            if (owner.retired) {
                // A recreated viewer can reuse its injected feeder. Keep the old state retired so
                // already queued messages and teardown callbacks cannot affect this new view.
                owner = new Owner();
                OWNERS.put(feeder, owner);
            }
            owner.authoritative = true;
            if (room.equals(owner.room)) return;
            owner.room = room;owner.generation++;
            detach(owner);
        }
    }

    /** View teardown retires only its feeder; a late old callback cannot detach a newer viewer. */
    public static void dispose(Object feeder) {
        if (feeder == null) return;
        synchronized (OWNERS) {
            Owner owner = OWNERS.computeIfAbsent(feeder, key -> new Owner());
            owner.retired = true;owner.generation++;
            detach(owner);
        }
    }

    private static void detach(Owner owner) {
        MAIN.post(() -> { if (overlay != null && overlay.owner == owner) detach(); });
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** Landscape and full size: not in portrait, and not in picture-in-picture or multi-window. */
    private static boolean isLandscape(Activity activity) {
        return activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                && !activity.isInPictureInPictureMode() && !activity.isInMultiWindowMode();
    }

    /** Main thread: shows a message, attaching the overlay if the activity is showing landscape video. */
    private static void show(Activity activity, String name, String text) {
        final Owner owner;
        synchronized (OWNERS) { owner = OWNERS.computeIfAbsent(activity, key -> new Owner()); }
        show(activity, name, text, owner);
    }

    private static void show(Activity activity, String name, String text, Owner owner) {
        if (!Settings.isEnabled(KEY) || activity.isFinishing() || activity.isDestroyed() || !isLandscape(activity)) {
            if (overlay != null && overlay.owner == owner) detach();
            return;
        }
        Overlay current = overlay;
        if (current == null || current.activity != activity || current.owner != owner ||
                !current.room.equals(owner.room) || !current.isAttachedToWindow()) {
            detach();
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) return;
            if (video(content.getRootView()) == null) return;
            current = new Overlay(activity, owner);
            ((FrameLayout) content).addView(current, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overlay = current;
        }
        current.add(name, text);
    }

    /** Main thread: removes the overlay, if any. */
    private static void detach() {
        Overlay current = overlay;
        overlay = null;
        if (current == null) return;
        current.stop();
        if (current.getParent() instanceof ViewGroup) ((ViewGroup) current.getParent()).removeView(current);
    }

    /** The largest visible video surface under {@code root} covering at least half its width, or null. */
    private static View video(View root) {
        if (root == null || root.getWidth() == 0) return null;
        View[] best = {null};
        int[] bestWidth = {root.getWidth() / 2};
        findVideo(root, best, bestWidth);
        return best[0];
    }

    private static void findVideo(View view, View[] best, int[] bestWidth) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof SurfaceView || view instanceof TextureView) {
            Rect rect = new Rect();
            if (view.isShown() && view.getGlobalVisibleRect(rect) && rect.width() >= bestWidth[0]) {
                best[0] = view;
                bestWidth[0] = rect.width();
            }
            return;
        }
        if (view instanceof ViewGroup && !(view instanceof Overlay)) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) findVideo(group.getChildAt(i), best, bestWidth);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Message text, read through each model's own toString labels (its field names are obfuscated)

    /** The sender's display name ("displayName=" in its toString), or "" if there is none. */
    private static String name(Object sender) throws ReflectiveOperationException {
        if (sender == null) return "";
        Field field = nameField;
        if (field == null || nameClass != sender.getClass()) {
            field = labelled(sender, "displayName=");
            if (field == null) field = labelled(sender, "username=");
            if (field == null) return "";
            nameClass = sender.getClass();
            nameField = field;
        }
        Object name = field.get(sender);
        return name instanceof String ? (String) name : "";
    }

    /** The message as plain text: text, mentions, links and emote codes, in order. */
    static String text(List tokens) throws ReflectiveOperationException {
        StringBuilder out = new StringBuilder();
        for (Object token : tokens) {
            if (token == null) continue;
            Field field = TEXT_FIELDS.get(token.getClass());
            if (field == null) {
                field = labelled(token, "text=");
                if (field == null) field = labelled(token, "url=");
                TEXT_FIELDS.put(token.getClass(), field == null ? NO_FIELD : field);
            }
            if (field == NO_FIELD) continue;
            Object text = field.get(token);
            if (text instanceof String) out.append((String) text);
            if (out.length() > MAX_TEXT) break;
        }
        String text = out.toString().replace('\n', ' ').trim();
        return text.codePointCount(0, text.length()) > MAX_TEXT
                ? text.substring(0, text.offsetByCodePoints(0, MAX_TEXT)) + "…" : text;
    }

    /** The String field of {@code model} whose value its toString prints after {@code label}. */
    private static Field labelled(Object model, String label) throws ReflectiveOperationException {
        String described = model.toString();
        for (Class<?> type = model.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != String.class || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object value = field.get(model);
                if (!(value instanceof String)) continue;
                String after = label + value;
                int at = described.indexOf(after);
                if (at < 0) continue;
                int end = at + after.length();
                if (end == described.length() || described.charAt(end) == ',' || described.charAt(end) == ')') {
                    return field;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // The view

    /** Draws scrolling lines; never touchable, focusable or visible to accessibility. */
    @SuppressLint("ViewConstructor")
    private static final class Overlay extends View {
        private static final int MAX_WAITING = 20;
        private static final long IDLE_MS = 15_000;
        private static final long CHECK_MS = 1_000;

        final Activity activity;
        final Owner owner;
        final String room;
        private final List<Item> items = new ArrayList<>();
        private final ArrayDeque<Item> waiting = new ArrayDeque<>();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint nameFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint nameOutline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect band = new Rect();
        private final float gap;
        private final float lineHeight;
        private long lastFrame;
        private long lastMessage;
        private boolean running = true;
        private final Runnable check = this::check;

        Overlay(Activity activity, Owner owner) {
            super(activity);
            this.activity = activity;
            this.owner = owner;
            synchronized (OWNERS) { room = owner.room; }
            setClickable(false);
            setLongClickable(false);
            setFocusable(false);
            setFocusableInTouchMode(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            float textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics());
            float stroke = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3, getResources().getDisplayMetrics());
            for (Paint paint : new Paint[]{fill, outline, nameFill, nameOutline}) {
                paint.setTextSize(textSize);
                paint.setTypeface(paint == fill || paint == outline ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
            }
            fill.setColor(Color.WHITE);
            nameFill.setColor(Color.WHITE);
            for (Paint paint : new Paint[]{outline, nameOutline}) {
                paint.setColor(0xE0000000);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setStrokeJoin(Paint.Join.ROUND);
            }
            Paint.FontMetrics metrics = fill.getFontMetrics();
            lineHeight = (metrics.descent - metrics.ascent) * 1.25f;
            gap = textSize * 2;
            postDelayed(check, CHECK_MS);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            return false;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return false;
        }

        @Override
        protected void onConfigurationChanged(Configuration configuration) {
            super.onConfigurationChanged(configuration);
            if (!isLandscape(activity)) detachSoon();
        }

        void stop() {
            running = false;
            removeCallbacks(check);
            items.clear();
            waiting.clear();
        }

        void add(String name, String text) {
            lastMessage = SystemClock.uptimeMillis();
            String label = name.isEmpty() ? "" : name + ": ";
            Item item = new Item(label, text, nameFill.measureText(label) + fill.measureText(text));
            if (waiting.size() >= MAX_WAITING) waiting.pollFirst();
            waiting.addLast(item);
            if (lastFrame == 0) lastFrame = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }

        /** Once a second: detach in portrait, without video, when chat stops or when switched off. */
        private void check() {
            if (!running) return;
            boolean idle = items.isEmpty() && waiting.isEmpty() && SystemClock.uptimeMillis() - lastMessage > IDLE_MS;
            if (idle || !Settings.isEnabled(KEY) || !isLandscape(activity) || !updateBand()) {
                detachSoon();
                return;
            }
            postDelayed(check, CHECK_MS);
        }

        private void detachSoon() {
            MAIN.post(() -> {
                if (overlay == this) detach();
                else stop();
            });
        }

        /** The top part of the video, in this view's coordinates; false when there is no video. */
        private boolean updateBand() {
            View video = video(getRootView());
            if (video == null) return false;
            Rect videoRect = new Rect();
            video.getGlobalVisibleRect(videoRect);
            int[] origin = new int[2];
            getLocationOnScreen(origin);
            int[] videoOrigin = new int[2];
            video.getLocationOnScreen(videoOrigin);
            int top = Math.max(videoOrigin[1] - origin[1], 0) + Math.round(lineHeight * 0.3f);
            int lines = Math.max(1, Math.min(lines(), (int) (videoRect.height() * 0.45f / lineHeight)));
            band.set(Math.max(videoOrigin[0] - origin[0], 0), top, Math.min(videoOrigin[0] - origin[0] + videoRect.width(), getWidth()),
                    top + Math.round(lines * lineHeight));
            return true;
        }

        private static int lines() {
            int lines = Settings.getInt(KEY_LINES);
            return lines <= 0 ? 5 : Math.min(lines, 12);
        }

        private float speed() {
            float dp;
            switch (Settings.getInt(KEY_SPEED)) {
                case 0:
                    dp = 90;
                    break;
                case 2:
                    dp = 220;
                    break;
                default:
                    dp = 140;
            }
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!running) return;
            if (band.isEmpty() && !updateBand()) return;
            long now = SystemClock.uptimeMillis();
            float step = (now - lastFrame) / 1000f * speed();
            lastFrame = now;
            int lanes = Math.max(1, Math.round(band.height() / lineHeight));
            for (Iterator<Item> it = items.iterator(); it.hasNext(); ) {
                Item item = it.next();
                item.x -= step;
                if (item.x + item.width < band.left) it.remove();
            }
            while (!waiting.isEmpty()) {
                int lane = freeLane(lanes);
                if (lane < 0) break;
                Item item = waiting.pollFirst();
                item.lane = lane;
                item.x = band.right;
                items.add(item);
            }
            Paint.FontMetrics metrics = fill.getFontMetrics();
            for (Item item : items) {
                float baseline = band.top + item.lane * lineHeight + (lineHeight - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent;
                if (!item.label.isEmpty()) {
                    canvas.drawText(item.label, item.x, baseline, nameOutline);
                    canvas.drawText(item.label, item.x, baseline, nameFill);
                }
                float x = item.x + item.labelWidth(nameFill);
                canvas.drawText(item.text, x, baseline, outline);
                canvas.drawText(item.text, x, baseline, fill);
            }
            if (!items.isEmpty() || !waiting.isEmpty()) postInvalidateOnAnimation();
        }

        /** The top lane whose newest line has fully entered with a gap after it, or -1. */
        private int freeLane(int lanes) {
            float[] tails = new float[lanes];
            for (Item item : items) {
                if (item.lane < lanes) tails[item.lane] = Math.max(tails[item.lane], item.x + item.width);
            }
            for (int lane = 0; lane < lanes; lane++) {
                if (tails[lane] + gap <= band.right) return lane;
            }
            return -1;
        }
    }

    private static final class Item {
        final String label;
        final String text;
        final float width;
        float x;
        int lane;
        private float labelWidth = -1;

        Item(String label, String text, float width) {
            this.label = label;
            this.text = text;
            this.width = width;
        }

        float labelWidth(Paint paint) {
            if (labelWidth < 0) labelWidth = paint.measureText(label);
            return labelWidth;
        }
    }
}
