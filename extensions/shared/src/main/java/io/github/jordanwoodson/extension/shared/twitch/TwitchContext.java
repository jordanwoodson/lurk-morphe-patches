package io.github.jordanwoodson.extension.shared.twitch;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import io.github.jordanwoodson.extension.shared.Logger;

/**
 * Context lookups the Twitch hooks share. TwitchPlayer keeps its own copies: the shared sleep-timer host
 * test compiles it without this class.
 */
final class TwitchContext {
    /** The application, found once; tests may set it. */
    static volatile Context application;

    private TwitchContext() {
    }

    /** The activity a view's or window's context belongs to, or null. */
    static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** The application context, or null before the app has one. */
    static Context application() {
        Context current = application;
        if (current == null) {
            try {
                current = (Context) Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
                application = current;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return current;
    }

    /** A short toast on the main thread, from [context]'s application. */
    static void toast(Context context, String text, String what) {
        // Made here, not in a static field: classes that only need the lookups load without a main looper.
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_SHORT).show();
            } catch (RuntimeException e) {
                Logger.d(what + ": toast " + e);
            }
        });
    }
}
