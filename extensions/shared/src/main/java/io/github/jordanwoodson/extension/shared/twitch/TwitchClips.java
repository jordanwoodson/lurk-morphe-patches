package io.github.jordanwoodson.extension.shared.twitch;

import android.app.DownloadManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Environment;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLEncoder;

import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * Clip download: a "Download clip" row in the menu (⋮) of a clip in Twitch's clips feed. The clip's MP4 addresses
 * and their signed access token come from gql.twitch.tv with the app's Client-ID only; the best quality goes to
 * Android's DownloadManager, which saves it to Downloads/Twitch and shows its progress.
 */
@SuppressWarnings("unused")
public final class TwitchClips {
    static final String KEY = "clip_download";
    private static final String GQL = "https://gql.twitch.tv/gql";
    private static final String ARGUMENTS =
            "tv.twitch.android.feature.discovery.feed.overflow.DiscoveryFeedOverflowMenuFragment$FragmentArguments";
    private static final long REQUEST_MS = 10_000;
    private static final Object TAG = new Object();

    private TwitchClips() {
    }

    /**
     * Injection point: the clips feed's menu sheet was bound to its presenter. Adds the download row above the
     * sheet's Cancel button when the item is a clip.
     */
    public static void overflowMenu(Object presenter, Object viewDelegate) {
        if (presenter == null || viewDelegate == null || !Settings.isEnabled(KEY)) return;
        try {
            Object clip = clipOf(presenter);
            View root = viewOf(viewDelegate);
            if (clip == null || !(root instanceof LinearLayout)) return;
            LinearLayout sheet = (LinearLayout) root;
            for (int i = 0; i < sheet.getChildCount(); i++) {
                if (sheet.getChildAt(i).getTag() == TAG) return;
            }
            String slug = (String) call(clip, "getClipSlugId");
            if (slug == null || slug.isEmpty()) return;
            String title = (String) call(clip, "getTitle");
            String channel = (String) call(clip, "getBroadcasterName");
            View cancel = child(sheet, "cancel_button");
            TextView row = row(sheet.getContext(), reference(sheet, "report_clip_curator_text"),
                    cancel instanceof TextView ? (TextView) cancel : null, child(sheet, "report_clip_curator_button"));
            row.setOnClickListener(view -> {
                if (cancel != null) cancel.performClick();
                download(view.getContext().getApplicationContext(), slug, title, channel);
            });
            int at = cancel == null ? sheet.getChildCount() : sheet.indexOfChild(cancel);
            sheet.addView(row, Math.max(0, at));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Logger.d("clips: menu " + e);
        }
    }

    /** The clip of the menu's feed item, or null for a stream. */
    private static Object clipOf(Object presenter) throws ReflectiveOperationException {
        for (Field field : presenter.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || !field.getType().getName().equals(ARGUMENTS)) continue;
            field.setAccessible(true);
            Object arguments = field.get(presenter);
            Object item = arguments == null ? null : call(arguments, "getFeedItem");
            if (item == null) return null;
            for (Method method : item.getClass().getMethods()) {
                if (method.getName().equals("getClipModel") && method.getParameterTypes().length == 0) return method.invoke(item);
            }
            return null;
        }
        return null;
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }

    /** The view delegate's root view (its one View field, declared on the delegate base class). */
    private static View viewOf(Object delegate) throws IllegalAccessException {
        for (Class<?> type = delegate.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != View.class) continue;
                field.setAccessible(true);
                return (View) field.get(delegate);
            }
        }
        return null;
    }

    private static View child(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
        return id == 0 ? null : root.findViewById(id);
    }

    private static TextView reference(View root, String name) {
        View view = child(root, name);
        return view instanceof TextView ? (TextView) view : null;
    }

    /**
     * A sheet row in the style of the sheet's own rows: [like]'s text size and typeface, [colors]' neutral text
     * color (the report rows are red), and [shape]'s background.
     */
    private static TextView row(Context context, TextView like, TextView colors, View shape) {
        TextView row = new TextView(context);
        row.setTag(TAG);
        row.setText("Download clip");
        row.setGravity(Gravity.CENTER_VERTICAL);
        float density = context.getResources().getDisplayMetrics().density;
        int horizontal = Math.round(16 * density);
        int vertical = Math.round(14 * density);
        row.setPadding(horizontal, vertical, horizontal, vertical);
        row.setCompoundDrawablePadding(horizontal);
        if (like != null) {
            row.setTextSize(TypedValue.COMPLEX_UNIT_PX, like.getTextSize());
            row.setTypeface(like.getTypeface());
        } else {
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        }
        ColorStateList text = colors != null ? colors.getTextColors() : ColorStateList.valueOf(0xFFFFFFFF);
        row.setTextColor(text);
        Drawable icon = context.getDrawable(android.R.drawable.stat_sys_download);
        if (icon != null) {
            icon = icon.mutate();
            int size = Math.round(24 * density);
            icon.setBounds(0, 0, size, size);
            icon.setTintList(text);
            row.setCompoundDrawablesRelative(icon, null, null, null);
        }
        Drawable background = shape == null || shape.getBackground() == null || shape.getBackground().getConstantState() == null
                ? null : shape.getBackground().getConstantState().newDrawable(context.getResources()).mutate();
        if (background != null) {
            row.setBackground(background);
        } else {
            TypedValue ripple = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                row.setBackgroundResource(ripple.resourceId);
            }
        }
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // Full width inside the sheet's padding, like its Cancel button, with a gap above and below.
        int gap = Math.round(8 * density);
        params.setMargins(0, gap, 0, gap);
        row.setLayoutParams(params);
        return row;
    }

    /** Looks the clip's MP4 up and hands the best quality to DownloadManager. Off the main thread. */
    static void download(Context context, String slug, String title, String channel) {
        TwitchContext.toast(context, "Downloading clip", "clips");
        Thread thread = new Thread(() -> {
            String url = mp4(slug);
            if (url == null) {
                TwitchContext.toast(context, "Couldn't find this clip's video", "clips");
                return;
            }
            try {
                DownloadManager manager = context.getSystemService(DownloadManager.class);
                String name = fileName(channel, title, slug);
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                        .setTitle(title == null || title.isEmpty() ? slug : title)
                        .setDescription("Twitch clip")
                        .setMimeType("video/mp4")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Twitch/" + name);
                long id = manager.enqueue(request);
                Logger.d("clips: download " + id + " of " + slug + " to Downloads/Twitch/" + name);
            } catch (RuntimeException e) {
                Logger.d("clips: download " + e);
                TwitchContext.toast(context, "Couldn't start the download", "clips");
            }
        }, "lurk-clip-download");
        thread.setDaemon(true);
        thread.start();
    }

    /** The clip's best-quality MP4 address with its signed access token, or null. */
    static String mp4(String slug) {
        try {
            JSONObject payload = new JSONObject()
                    .put("query", "query($slug: ID!) { clip(slug: $slug) { videoQualities { quality sourceURL } "
                            + "playbackAccessToken(params: {platform: \"android\", playerType: \"mobile_player\"}) "
                            + "{ signature value } } }")
                    .put("variables", new JSONObject().put("slug", slug));
            String response = TwitchRequest.http(GQL, payload.toString(), TwitchRequest.CLIENT_ID,
                    TwitchRequest.now() + REQUEST_MS, () -> Settings.isEnabled(KEY));
            return response == null ? null : signedUrl(response);
        } catch (Exception e) {
            Logger.d("clips: lookup " + e);
            return null;
        }
    }

    /** The highest quality's address from a clip response, with ?sig=&token= added; null without one. */
    static String signedUrl(String response) {
        try {
            JSONObject clip = new JSONObject(response).getJSONObject("data").optJSONObject("clip");
            if (clip == null) return null;
            JSONArray qualities = clip.optJSONArray("videoQualities");
            JSONObject token = clip.optJSONObject("playbackAccessToken");
            if (qualities == null || token == null) return null;
            String best = null;
            int height = -1;
            for (int i = 0; i < qualities.length(); i++) {
                JSONObject quality = qualities.optJSONObject(i);
                if (quality == null) continue;
                String url = quality.optString("sourceURL", "");
                int value = parse(quality.optString("quality", ""));
                if (url.startsWith("https://") && value > height) {
                    best = url;
                    height = value;
                }
            }
            String signature = token.optString("signature", "");
            String value = token.optString("value", "");
            if (best == null || signature.isEmpty() || value.isEmpty()) return null;
            return best + (best.contains("?") ? "&" : "?") + "sig=" + URLEncoder.encode(signature, "UTF-8")
                    + "&token=" + URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            Logger.d("clips: response " + e);
            return null;
        }
    }

    private static int parse(String quality) {
        try {
            return Integer.parseInt(quality.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** channel-title-slug.mp4 with only safe characters. */
    static String fileName(String channel, String title, String slug) {
        StringBuilder name = new StringBuilder();
        for (String part : new String[]{channel, title, slug}) {
            if (part == null || part.isEmpty()) continue;
            String safe = part.replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^_+|_+$", "");
            if (safe.isEmpty()) continue;
            if (name.length() > 0) name.append('-');
            name.append(safe.length() > 60 ? safe.substring(0, 60) : safe);
        }
        if (name.length() == 0) name.append("clip");
        return name + ".mp4";
    }
}
