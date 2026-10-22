package io.github.jordanwoodson.extension.shared.kick;

import android.content.res.AssetManager;
import android.util.Log;
import io.github.jordanwoodson.extension.shared.CompiledBundle;
import io.github.jordanwoodson.extension.shared.Logger;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;

/** Kick's data-only callable module. Its JS source is compiled by Gradle with the pinned HBC99 compiler. */
public final class KickComposer {
    private static final String ASSET = "lurk-kick-chat";
    private static final String UNAVAILABLE = "7TV emotes: the picker and autocomplete part didn't load, so 7TV emotes "
            + "show in chat messages only: ";
    /** The React instance the bundle was queued on; CompiledBundle.load also returns false for that one. */
    private static WeakReference<Object> loaded = new WeakReference<>(null);
    private KickComposer() { }

    public static void afterMain(Object instance, AssetManager assets, String sourceUrl) {
        if (!"assets://index.android.bundle".equals(sourceUrl) || !KickChat.on(KickChat.SEVEN_TV)) return;
        if (!CompiledBundle.load(instance, assets, ASSET, 99)) {
            if (loaded.get() != instance) Log.w(Logger.TAG, UNAVAILABLE + "see the Compiled Hermes line above");
            return;
        }
        loaded = new WeakReference<>(instance);
        try {
            send("configure", new String(CompiledBundle.read(assets, ASSET + "-contract.json", 8192), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(Logger.TAG, UNAVAILABLE + "missing contract " + e);
        }
    }

    public static void afterFile(Object instance, String sourceUrl) {
        try {
            Object context = instance.getClass().getMethod("access$getContext$p", instance.getClass()).invoke(null, instance);
            afterMain(instance, ((android.content.Context) context).getAssets(), sourceUrl);
        } catch (Exception e) {
            Log.w(Logger.TAG, UNAVAILABLE + "no context for the prepared bundle " + e);
        }
    }

    static void send(String method, String json) {
        CompiledBundle.call(ASSET, "LurkKickChat", method, json);
    }
}
