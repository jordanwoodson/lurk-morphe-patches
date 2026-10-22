import java.lang.reflect.Method;

/**
 * Remember playback speed, Keep playing during other sounds and Keep screen on while paused: runs Rumble's
 * speed store and player states through the extension, with each setting on and off.
 */
public final class PlayerProbe {
    private static final String PLAYBACK = "io.github.jordanwoodson.extension.shared.rumble.RumblePlayback";
    private static final String PLAYER = "io.github.jordanwoodson.extension.shared.rumble.RumblePlayer";

    public static void main(String[] args) {
        ProbeSupport.run("remembered speed, focus choices and screen on while paused", PlayerProbe::run);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void run() throws Exception {
        // The speed store (a Kotlin object) and its setter and getter, as the patch matched them; the scope and
        // speed enums are their parameter types, and the reset is the store's one ()V method.
        Method set = ProbeSupport.method("rumble.remember_speed.store_set", "LPg/f;->c(LPg/q;LPg/i;)V");
        Method get = ProbeSupport.method("rumble.remember_speed.store_get", "LPg/f;->a(LPg/q;)LPg/i;");
        Class<?> storeType = set.getDeclaringClass();
        Object store = ProbeSupport.staticOfType(storeType, storeType);
        Class scopeType = set.getParameterTypes()[0];
        Class speedType = set.getParameterTypes()[1];
        Object scope = Enum.valueOf(scopeType, "VideoDetails");
        Object fast = Enum.valueOf(speedType, "SPEED_1_5");
        Object normal = Enum.valueOf(speedType, "NORMAL");
        Method resetAll = ProbeSupport.bySignature(storeType, void.class);

        set.invoke(store, scope, fast);
        resetAll.invoke(store);
        ProbeSupport.check(get.invoke(store, scope) == normal, "switch off (default): 1x after the service reset");
        ProbeSupport.setting("remember_speed", "true");
        set.invoke(store, scope, fast);
        resetAll.invoke(store);
        ProbeSupport.check(get.invoke(store, scope) == fast, "switch on: the picked 1.5x survives the reset");
        ProbeSupport.check(get.invoke(store, Enum.valueOf(scopeType, "Other")) == fast, "and applies to every scope");
        ProbeSupport.setting("remember_speed", "false");
        set.invoke(store, scope, normal);
        resetAll.invoke(store);
        ProbeSupport.check(get.invoke(store, scope) == normal, "off: stock reset and getter return 1x");
        ProbeSupport.setting("remember_speed", "true");
        ProbeSupport.check(get.invoke(store, scope) == fast, "on again restores the previous saved speed");
        set.invoke(store, scope, normal);
        ProbeSupport.check(get.invoke(store, scope) == normal, "an enabled pick replaces the saved speed");

        // Default playback speed: after Rumble's reset and until a pick, when Remember playback speed has nothing.
        if (ProbeSupport.registered("default_speed")) {
            Object fastest = Enum.valueOf(speedType, "SPEED_1_5");
            ProbeSupport.setting("remember_speed", "false");
            resetAll.invoke(store);
            ProbeSupport.check(get.invoke(store, scope) == normal, "default speed off (its default): 1x");
            ProbeSupport.setting("default_speed", "150");
            ProbeSupport.check(get.invoke(store, scope) == fastest, "default speed 1.5x after a reset");
            set.invoke(store, scope, fast);
            ProbeSupport.check(get.invoke(store, scope) == fast, "a pick wins over the default speed");
            resetAll.invoke(store);
            ProbeSupport.check(get.invoke(store, scope) == fastest, "the next reset starts at the default again");
            ProbeSupport.setting("default_speed", "0");
        }

        Method ignore = Class.forName(PLAYBACK).getMethod("ignoreFocusLoss", int.class);
        ProbeSupport.check(!(boolean) ignore.invoke(null, -2), "Rumble's default pauses on a transient loss");
        ProbeSupport.setting("audio_focus", "1");
        ProbeSupport.check((boolean) ignore.invoke(null, -2) && (boolean) ignore.invoke(null, -3),
                "short sounds are ignored with choice 1");
        ProbeSupport.check(!(boolean) ignore.invoke(null, -1), "a permanent loss still pauses with choice 1");
        ProbeSupport.check(!(boolean) ignore.invoke(null, 1), "gains are never ignored");
        ProbeSupport.setting("audio_focus", "2");
        ProbeSupport.check((boolean) ignore.invoke(null, -1), "every loss is ignored with choice 2");

        // The player state effect passes the state it read (the Paused state's class and its siblings).
        Object paused = state("Paused(");
        Object playing = state("Playing(");
        Method keep = Class.forName(PLAYER).getMethod("keepScreenOn", boolean.class, Object.class);
        ProbeSupport.check(!(boolean) keep.invoke(null, false, paused), "switch off (default): paused turns the screen off");
        ProbeSupport.setting("keep_screen_on_paused", "true");
        ProbeSupport.check((boolean) keep.invoke(null, false, paused), "switch on: paused keeps the screen on");
        ProbeSupport.check(!(boolean) keep.invoke(null, false, playing), "other states keep Rumble's answer");
        ProbeSupport.check((boolean) keep.invoke(null, true, paused), "Rumble's true stays true");
    }

    /**
     * A player state whose toString starts with {@code label}: one of the subclasses of the Paused state's parent
     * (R8 names them $a, $b, ...).
     */
    private static Object state(String label) throws Exception {
        String parent = ProbeSupport.type("rumble.keep_screen_on_paused.paused_state", "LPg/j$e;").getSuperclass().getName();
        for (char c = 'a'; c <= 'h'; c++) {
            try {
                Object state = ProbeSupport.allocate(Class.forName(parent + "$" + c));
                if (state.toString().startsWith(label)) return state;
            } catch (ClassNotFoundException ignored) {
                // Not a state class.
            }
        }
        throw new AssertionError("no player state " + label);
    }
}
