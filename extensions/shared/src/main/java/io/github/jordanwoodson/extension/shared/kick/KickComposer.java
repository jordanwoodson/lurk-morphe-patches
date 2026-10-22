package io.github.jordanwoodson.extension.shared.kick;

import android.content.res.AssetManager;
import io.github.jordanwoodson.extension.shared.CompiledBundle;
import io.github.jordanwoodson.extension.shared.Logger;
import java.nio.charset.StandardCharsets;

/** Kick's data-only callable module. Its JS source is compiled by Gradle with the pinned HBC99 compiler. */
public final class KickComposer {
    private static final String ASSET = "lurk-kick-chat";
    private KickComposer() { }

    public static void afterMain(Object instance, AssetManager assets, String sourceUrl) {
        if (!"assets://index.android.bundle".equals(sourceUrl) || !KickChat.on(KickChat.SEVEN_TV)) return;
        if (!CompiledBundle.load(instance, assets, ASSET, 99)) return;
        try {
            send("configure", new String(CompiledBundle.read(assets, ASSET + "-contract.json", 8192), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Logger.d("Kick composer: missing contract");
        }
    }

    public static void afterFile(Object instance, String sourceUrl) {
        try {
            Object context = instance.getClass().getMethod("access$getContext$p", instance.getClass()).invoke(null, instance);
            afterMain(instance, ((android.content.Context) context).getAssets(), sourceUrl);
        } catch (Exception e) {
            Logger.d("Kick composer: file context unavailable");
        }
    }

    static void send(String method, String json) {
        CompiledBundle.call(ASSET, "LurkKickChat", method, json);
    }
}
