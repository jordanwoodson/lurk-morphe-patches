package io.github.jordanwoodson.extension.shared.twitch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * VOD chapters: shows a video's chapters (game/category changes) from Twitch's GQL and seeks to one
 * through Twitch's own VOD seek path. The VOD and the seek target come from the player presenter whose
 * video sits under the tapped overlay, so a preloaded neighbour never answers for it. With the setting
 * off the button never appears and nothing is fetched.
 */
@SuppressWarnings("unused")
public final class TwitchVod {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String GQL = "https://gql.twitch.tv/gql";
    // The Twitch Android app's public Client-ID. The "moments" query used here is public, so it is
    // answered without an account token.
    private static final String CLIENT_ID = "kd1unb4b3q4t58fwlpcbzcbnm76a8fp";

    private TwitchVod() {
    }

    /** One chapter: where it starts in the VOD and what it is. */
    private static final class Chapter {
        final int positionMs;
        final String label;

        Chapter(int positionMs, String label) {
            this.positionMs = positionMs;
            this.label = label;
        }
    }

    private static final class Attached implements View.OnAttachStateChangeListener {
        final WeakReference<View> view;
        final WeakReference<Object> presenter;
        final long order;
        volatile boolean detached;

        Attached(Object presenter, View view, long order) {
            this.view = new WeakReference<>(view);
            this.presenter = new WeakReference<>(presenter);
            this.order = order;
        }

        @Override public void onViewAttachedToWindow(View view) {
            Object owner = presenter.get();
            synchronized (PLAYER_VIEWS) {
                if (owner == null || PLAYER_VIEWS.get(owner) != this) return;
                detached = false;
            }
            String id = vodOf(owner);
            if (id != null) request(new Pending(owner, null, id));
            MAIN.post(TwitchVod::refreshButtons);
        }

        @Override public void onViewDetachedFromWindow(View view) {
            Object owner = presenter.get();
            synchronized (PLAYER_VIEWS) {
                if (owner == null || PLAYER_VIEWS.get(owner) != this) return;
                detached = true;
            }
            // Lifecycle callbacks run on main. Permanently retire this attachment's old demand;
            // a reattach may request afresh, while another owner can keep a shared load alive.
            synchronized (REQUESTS) {
                for (Load load : new ArrayList<>(LOADS.values())) {
                    for (Pending pending : load.waiters) {
                        if (pending.presenter.get() == owner) pending.valid = false;
                    }
                    if (!load.active()) { LOADS.remove(load.id); FETCHES.remove(load); }
                }
                for (Pending pending : WAITING.values()) {
                    if (pending.presenter.get() == owner) pending.valid = false;
                }
                postDelivery();
            }
            MAIN.post(TwitchVod::refreshButtons);
        }
    }

    /** Every native player presenter (live, VOD, clip) and the player view it last attached. */
    private static final Map<Object, Attached> PLAYER_VIEWS = new WeakHashMap<>();
    /** VOD presenters and the video each one loaded last. */
    private static final Map<Object, String> VODS = new WeakHashMap<>();
    /** The most recently loaded VOD presenter: the fallback when an overlay can't be resolved. */
    private static WeakReference<Object> latest = new WeakReference<>(null);
    private static long attachOrder;

    /** Chapters per VOD id, so the dialog opens instantly and taps don't refetch. */
    private static final Object REQUESTS = new Object();
    private static final Map<String, List<Chapter>> CACHE = bounded(32);
    private static final Map<String, Long> FAILURES = bounded(32);
    private static final Map<String, Load> LOADS = new HashMap<>();
    /** Weak UI ownership; at most one pending open per button, 32 overall. */
    private static final Map<View, Pending> WAITING = new WeakHashMap<>();
    private static boolean deliveryPosted;
    private static final long REQUEST_MS = 6000, RETRY_MS = 15000;
    private static final ThreadPoolExecutor FETCHES = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "lurk-chapters");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private static <V> Map<String, V> bounded(int limit) {
        return new LinkedHashMap<String, V>(16, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, V> entry) { return size() > limit; }
        };
    }
    /** The Chapters buttons Lurk added, each with the overlay root it sits in. */
    private static final Map<View, WeakReference<View>> BUTTONS = new WeakHashMap<>();
    private static int chaptersDrawable;

    /**
     * Injection point: a native player presenter attaching its player view (em2.y0). Live, clip and VOD
     * presenters all pass here, so an overlay over a clip resolves to the clip, not to an older VOD.
     */
    public static void playerView(Object presenter, View view) {
        if (presenter == null || view == null || !Settings.isEnabled("vod_chapters")) return;
        Attached previous;
        Attached attached;
        synchronized (PLAYER_VIEWS) {
            attached = new Attached(presenter, view, ++attachOrder);
            previous = PLAYER_VIEWS.put(presenter, attached);
        }
        if (previous != null) {
            View oldView = previous.view.get();
            if (oldView != null) oldView.removeOnAttachStateChangeListener(previous);
        }
        view.addOnAttachStateChangeListener(attached);
        MAIN.post(TwitchVod::refreshButtons);
    }

    /**
     * Injection point: the VOD presenter loading a video ({@code cll.q0(VodModel, int, String)}).
     * Records which video this presenter plays and prefetches its chapters.
     */
    public static void vodLoaded(Object vodPresenter, Object vodModel) {
        if (vodPresenter == null || vodModel == null || !Settings.isEnabled("vod_chapters")) return;
        try {
            String id = videoId((String) vodModel.getClass().getMethod("getId").invoke(vodModel));
            if (id == null) {
                synchronized (PLAYER_VIEWS) {
                    VODS.remove(vodPresenter);
                    if (latest.get() == vodPresenter) latest = new WeakReference<>(null);
                }
                MAIN.post(TwitchVod::refreshButtons);
                return;
            }
            synchronized (PLAYER_VIEWS) {
                VODS.put(vodPresenter, id);
                latest = new WeakReference<>(vodPresenter);
            }
            request(new Pending(vodPresenter, null, id));
            MAIN.post(TwitchVod::refreshButtons);
        } catch (Throwable t) {
            Logger.d("vod chapters: " + t);
        }
    }

    /**
     * GQL's video ID from VodModel's: Twitch keeps VOD IDs as "v123..." and strips the "v" itself before
     * its own playback-token query. Null when it isn't a numeric video ID.
     */
    static String videoId(String modelId) {
        if (modelId == null) return null;
        String id = modelId.startsWith("v") ? modelId.substring(1) : modelId;
        if (id.isEmpty()) return null;
        for (int i = 0; i < id.length(); i++) {
            if (!Character.isDigit(id.charAt(i))) return null;
        }
        return id;
    }

    /** Injection point: the VOD/clip overlay's settings gear, once found. Adds a Chapters button. */
    public static void bindChapters(View gear) {
        if (gear == null || !Settings.isEnabled("vod_chapters")) return;
        try {
            if (!(gear.getParent() instanceof View)) return;
            View overlay = (View) gear.getParent();
            View rotate = overlay.findViewById(id(gear, "rotate_button", "id"));
            if (!(rotate instanceof ImageView) || !(rotate.getParent() instanceof LinearLayout)) return;
            LinearLayout row = (LinearLayout) rotate.getParent();
            ImageView button = buildButton((ImageView) rotate);
            if (button == null) return;
            row.addView(button, Math.max(0, row.indexOfChild(rotate)));
            synchronized (BUTTONS) {
                BUTTONS.put(button, new WeakReference<>(overlay));
            }
            button.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    MAIN.post(() -> refresh(view));
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    synchronized (REQUESTS) {
                        Pending pending = WAITING.remove(view);
                        if (pending != null) pending.valid = false;
                    }
                }
            });
        } catch (Throwable t) {
            Logger.d("vod chapters: " + t);
        }
    }

    private static ImageView buildButton(ImageView sibling) {
        int drawable = chaptersDrawable(sibling);
        if (drawable == 0) return null;
        ImageView button = new ImageView(sibling.getContext());
        button.setId(View.generateViewId());
        button.setImageResource(drawable);
        button.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setContentDescription("Chapters");
        button.setLayoutParams(new LinearLayout.LayoutParams(sibling.getLayoutParams()));
        button.setPadding(sibling.getPaddingLeft(), sibling.getPaddingTop(),
                sibling.getPaddingRight(), sibling.getPaddingBottom());
        Drawable background = sibling.getBackground();
        if (background != null && background.getConstantState() != null) {
            button.setBackground(background.getConstantState().newDrawable().mutate());
        }
        button.setVisibility(View.GONE);
        button.setOnClickListener(TwitchVod::openChapters);
        return button;
    }

    // ---------------------------------------------------------------------------------------------
    // Which presenter an overlay belongs to

    /**
     * The presenter whose shown player view lies under the overlay's centre, in the same window, most
     * recently attached first. Null when the overlay isn't laid out or no player view covers it.
     */
    private static Object presenterFor(View overlay) {
        if (overlay == null || overlay.getWidth() == 0 || overlay.getHeight() == 0) return null;
        int[] location = new int[2];
        overlay.getLocationOnScreen(location);
        float x = location[0] + overlay.getWidth() / 2f;
        float y = location[1] + overlay.getHeight() / 2f;
        Object best = null;
        long bestOrder = -1;
        synchronized (PLAYER_VIEWS) {
            for (Map.Entry<Object, Attached> entry : PLAYER_VIEWS.entrySet()) {
                View view = entry.getValue().view.get();
                if (view == null || !view.isShown() || view.getWidth() == 0) continue;
                if (view.getRootView() != overlay.getRootView()) continue;
                view.getLocationOnScreen(location);
                boolean contains = x >= location[0] && x < location[0] + view.getWidth()
                        && y >= location[1] && y < location[1] + view.getHeight();
                if (contains && entry.getValue().order > bestOrder) {
                    best = entry.getKey();
                    bestOrder = entry.getValue().order;
                }
            }
        }
        return best;
    }

    private static View overlayOf(View button) {
        synchronized (BUTTONS) {
            WeakReference<View> overlay = BUTTONS.get(button);
            return overlay == null ? null : overlay.get();
        }
    }

    /** The VOD presenter for a button: its overlay's presenter, else the most recent VOD presenter. */
    private static Object targetFor(View button, boolean[] resolved) {
        Object presenter = presenterFor(overlayOf(button));
        resolved[0] = presenter != null;
        if (presenter != null) return presenter;
        synchronized (PLAYER_VIEWS) {
            return latest.get();
        }
    }

    private static String vodOf(Object presenter) {
        if (presenter == null) return null;
        synchronized (PLAYER_VIEWS) {
            return VODS.get(presenter);
        }
    }

    private static void refreshButtons() {
        synchronized (REQUESTS) {
            for (Load load : LOADS.values()) for (Pending pending : load.waiters) {
                if (!pending.current()) pending.valid = false;
            }
        }
        List<View> buttons;
        synchronized (BUTTONS) {
            buttons = new ArrayList<>(BUTTONS.keySet());
        }
        for (View button : buttons) refresh(button);
    }

    /** Shown when the overlay's own presenter plays a VOD (or, unresolved, when any VOD is loaded). */
    private static void refresh(View button) {
        if (button == null) return;
        boolean[] resolved = new boolean[1];
        Object presenter = targetFor(button, resolved);
        button.setVisibility(Settings.isEnabled("vod_chapters") && vodOf(presenter) != null ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------------------------------------
    // Dialog and seek

    private static void openChapters(View button) {
        if (!Settings.isEnabled("vod_chapters")) return;
        boolean[] resolved = new boolean[1];
        final Object presenter = targetFor(button, resolved);
        final String id = vodOf(presenter);
        if (id == null) {
            toast(button.getContext(), "No chapters for this video");
            return;
        }
        Logger.d("vod chapters: open " + id + (resolved[0] ? " (overlay)" : " (most recent)"));
        Pending pending = new Pending(presenter, button, id);
        synchronized (REQUESTS) {
            Pending previous = WAITING.remove(button);
            if (previous != null) previous.valid = false;
            if (WAITING.size() >= 32) {
                View oldest = WAITING.keySet().iterator().next();
                WAITING.remove(oldest).valid = false;
            }
            WAITING.put(button, pending);
        }
        request(pending);
    }

    /** An interested presenter, or an open dialog request. Neither keeps a player or Activity alive. */
    private static final class Pending {
        final WeakReference<Object> presenter;
        final WeakReference<View> button;
        final boolean open;
        final String id;
        volatile boolean valid = true;
        boolean ready;
        List<Chapter> result;

        Pending(Object presenter, View button, String id) {
            this.presenter = new WeakReference<>(presenter);
            this.button = new WeakReference<>(button);
            this.open = button != null;
            this.id = id;
        }

        // Safe on I/O workers; view inspection happens only on the main thread below.
        boolean active() {
            Object owner = presenter.get();
            if (!valid || !Settings.isEnabled("vod_chapters") || !id.equals(vodOf(owner))
                    || open && button.get() == null) return false;
            synchronized (PLAYER_VIEWS) {
                Attached attached = PLAYER_VIEWS.get(owner);
                return attached == null || !attached.detached;
            }
        }

        boolean current() {
            if (!active()) return false;
            View view = button.get();
            if (open) return view != null && view.isAttachedToWindow() && view.isShown()
                    && targetFor(view, new boolean[1]) == presenter.get();
            synchronized (PLAYER_VIEWS) {
                Attached attached = PLAYER_VIEWS.get(presenter.get());
                if (attached == null) return true; // prefetch can precede the player-view hook
                View player = attached.view.get();
                return player != null && player.isAttachedToWindow() && player.isShown()
                        && presenterFor(player) == presenter.get();
            }
        }
    }

    private static final class Load implements Runnable {
        final String id;
        final long deadline = TwitchRequest.now() + REQUEST_MS;
        final List<Pending> waiters = new ArrayList<>();
        Load(String id) { this.id = id; }

        boolean active() {
            synchronized (REQUESTS) {
                if (LOADS.get(id) != this) return false;
                for (Pending pending : waiters) if (pending.active()) return true;
                return false;
            }
        }

        @Override public void run() {
            List<Chapter> chapters = TwitchRequest.run(deadline, this::active, () -> fetch(id, deadline));
            synchronized (REQUESTS) {
                if (LOADS.get(id) != this) return;
                boolean wanted = active();
                LOADS.remove(id);
                if (!wanted) return;
                if (chapters == null) FAILURES.put(id, TwitchRequest.now() + RETRY_MS);
                else { CACHE.put(id, chapters); FAILURES.remove(id); }
                for (Pending pending : waiters) if (pending.open && pending.active()) {
                    pending.result = chapters;
                    pending.ready = true;
                }
                postDelivery();
            }
        }
    }

    /** One load per VOD, two running/eight queued, eight weak owners per load. */
    private static void request(Pending pending) {
        synchronized (REQUESTS) {
            if (!pending.active()) return;
            // Retired queued jobs must not occupy slots or start a provider request later.
            for (Load load : new ArrayList<>(LOADS.values())) if (!load.active()) {
                LOADS.remove(load.id);
                FETCHES.remove(load);
            }
            List<Chapter> cached = CACHE.get(pending.id);
            Long retry = FAILURES.get(pending.id);
            if (cached != null || retry != null && TwitchRequest.now() < retry) {
                pending.result = cached;
                pending.ready = true;
                postDelivery();
                return;
            }
            Load load = LOADS.get(pending.id);
            boolean start = load == null;
            if (start) {
                if (LOADS.size() >= 10) { pending.ready = true; postDelivery(); return; }
                load = new Load(pending.id);
                LOADS.put(pending.id, load);
            }
            load.waiters.removeIf(old -> !old.active() || old.presenter.get() == pending.presenter.get()
                    && old.open == pending.open && old.button.get() == pending.button.get());
            if (load.waiters.size() >= 8) { pending.ready = true; postDelivery(); return; }
            load.waiters.add(pending);
            if (start) try { FETCHES.execute(load); }
            catch (RuntimeException e) { LOADS.remove(load.id); pending.ready = true; postDelivery(); }
        }
    }

    /** Coalesced main-thread delivery; completed jobs cannot build an unbounded UI message queue. */
    private static void postDelivery() {
        if (!deliveryPosted) {
            deliveryPosted = true;
            MAIN.post(TwitchVod::deliverChapters);
        }
    }

    private static void deliverChapters() {
        List<Pending> ready = new ArrayList<>();
        synchronized (REQUESTS) {
            deliveryPosted = false;
            java.util.Iterator<Pending> iterator = WAITING.values().iterator();
            while (iterator.hasNext()) {
                Pending pending = iterator.next();
                if (!pending.active() || pending.ready) {
                    iterator.remove();
                    if (pending.ready) ready.add(pending);
                }
            }
        }
        for (Pending pending : ready) if (pending.current()) {
            View button = pending.button.get();
            if (button == null) continue;
            if (pending.result == null) toast(button.getContext(), "Couldn't load chapters");
            else show(button.getContext(), pending.presenter.get(), pending.id, pending.result);
        }
    }

    private static void show(Context context, Object presenter, String id, List<Chapter> chapters) {
        Activity activity = activity(context);
        if (!Settings.isEnabled("vod_chapters") || !id.equals(vodOf(presenter))
                || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (chapters.isEmpty()) {
            toast(context, "This video has no chapters");
            return;
        }
        CharSequence[] items = new CharSequence[chapters.size()];
        for (int i = 0; i < chapters.size(); i++) {
            Chapter chapter = chapters.get(i);
            items[i] = time(chapter.positionMs) + "   " + chapter.label;
        }
        final WeakReference<Object> target = new WeakReference<>(presenter);
        try {
            new AlertDialog.Builder(activity)
                    .setTitle("Chapters")
                    .setItems(items, (dialog, which) -> seekTo(target.get(), id, chapters.get(which).positionMs))
                    .show();
        } catch (RuntimeException e) {
            Logger.d("vod chapters: " + e);
        }
    }

    private static void seekTo(Object presenter, String id, int positionMs) {
        // The presenter must still play the video the list came from.
        if (!Settings.isEnabled("vod_chapters") || presenter == null || !id.equals(vodOf(presenter))) {
            Logger.d("vod chapters: the player moved on, not seeking");
            return;
        }
        try {
            seekVod(presenter, positionMs);
            Logger.d("vod chapters: seek " + id + " to " + positionMs + " ms");
        } catch (Throwable t) {
            Logger.d("vod chapters: seek " + t);
        }
    }

    /** Replaced at patch time: Twitch's own VOD seek ({@code cll.seekTo(int)}), position in ms. */
    private static void seekVod(Object vodPresenter, int positionMs) {
    }

    // ---------------------------------------------------------------------------------------------
    // Network

    /** Fetches and parses one VOD; the request coordinator alone owns caching and delivery. */
    private static List<Chapter> fetch(String id, long deadline) {
        try {
            JSONObject payload = new JSONObject()
                    .put("query", "query($id: ID!) { video(id: $id) { moments(first: 100, "
                            + "momentRequestType: VIDEO_CHAPTER_MARKERS) { edges { node { "
                            + "positionMilliseconds description type } } } } }")
                    .put("variables", new JSONObject().put("id", id));
            String response = TwitchRequest.http(GQL, payload.toString(), CLIENT_ID, deadline,
                    () -> Settings.isEnabled("vod_chapters"));
            return response == null ? null : parse(response);
        } catch (Exception e) {
            Logger.d("vod chapters: fetch " + e);
            return null;
        }
    }

    private static List<Chapter> parse(String response) {
        try {
            JSONObject video = new JSONObject(response).getJSONObject("data").optJSONObject("video");
            List<Chapter> chapters = new ArrayList<>();
            if (video == null) return chapters;
            JSONObject moments = video.optJSONObject("moments");
            JSONArray edges = moments == null ? null : moments.optJSONArray("edges");
            if (edges == null) return chapters;
            for (int i = 0; i < edges.length(); i++) {
                JSONObject node = edges.getJSONObject(i).optJSONObject("node");
                if (node == null) continue;
                String label = node.optString("description", "");
                if (label.isEmpty()) label = node.optString("type", "Chapter");
                chapters.add(new Chapter(node.optInt("positionMilliseconds", 0), label));
            }
            return chapters;
        } catch (Throwable t) {
            Logger.d("vod chapters: parse " + t);
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------

    private static int chaptersDrawable(View sibling) {
        if (chaptersDrawable == 0) chaptersDrawable = id(sibling, "lurk_chapters", "drawable");
        return chaptersDrawable;
    }

    /** A resource of the app by name, looked up in the app's own package (the clone keeps it). */
    private static int id(View view, String name, String type) {
        try {
            Resources resources = view.getContext().getResources();
            return resources.getIdentifier(name, type, view.getContext().getPackageName());
        } catch (RuntimeException e) {
            Logger.d("vod chapters: id " + e);
            return 0;
        }
    }

    private static String time(int ms) {
        int seconds = Math.max(0, ms / 1000);
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        if (hours > 0) return String.format("%d:%02d:%02d", hours, minutes, secs);
        return String.format("%d:%02d", minutes, secs);
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static void toast(Context context, String text) {
        MAIN.post(() -> {
            try {
                Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_SHORT).show();
            } catch (RuntimeException e) {
                Logger.d("vod chapters: toast " + e);
            }
        });
    }
}
