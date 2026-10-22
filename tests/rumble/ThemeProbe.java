import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * AMOLED black: runs the theme color set's constructor and the palette's static initializer the patch hooked.
 * Rumble's surface grey (#1B2127) becomes black while the switch is on and near-black with Near-black surfaces;
 * the divider grey (#283139) and every other color stay.
 */
public final class ThemeProbe {
    private static final long GREY = 0xFF1B2127L;
    private static final long DIVIDER = 0xFF283139L;
    private static final long BLACK = 0xFF000000L;
    private static final long NEAR_BLACK = 0xFF0B0B0CL;

    public static void main(String[] args) {
        ProbeSupport.run("AMOLED black surfaces in the theme colors and the palette", ThemeProbe::run);
    }

    private static void run() throws Exception {
        if (!ProbeSupport.registered("amoled_black")) {
            System.out.println("AMOLED black is not included: nothing to check");
            return;
        }

        // The palette's static initializer runs once per process: switch on before anything touches the class.
        ProbeSupport.setting("amoled_black", "true");
        Class<?> palette = owner(ProbeSupport.locate("rumble.amoled_black.palette", "LAg/f;-><clinit>()V"));
        Class.forName(palette.getName(), true, palette.getClassLoader());
        List<Long> constants = longs(palette, null);
        ProbeSupport.check(!constants.contains(color(GREY)), "palette: the surface grey must be black while on");
        ProbeSupport.check(constants.contains(color(BLACK)), "palette: black is there");
        ProbeSupport.check(constants.contains(color(DIVIDER)), "palette: the divider grey must stay");

        // The theme's color set, RumbleColors(isLight): one instance per theme, each with every color.
        String reference = ProbeSupport.locate("rumble.amoled_black.colors", "LAg/e;-><init>(Z)V");
        Constructor<?> colors = owner(reference).getDeclaredConstructor(boolean.class);
        colors.setAccessible(true);

        ProbeSupport.setting("amoled_black", "false");
        List<Long> off = longs(colors.getDeclaringClass(), colors.newInstance(false));
        ProbeSupport.check(off.contains(color(GREY)), "colors: Rumble's surface grey while off");
        ProbeSupport.check(off.contains(color(DIVIDER)), "colors: Rumble's divider grey while off");

        ProbeSupport.setting("amoled_near_black", "true");
        ProbeSupport.check(off.equals(longs(colors.getDeclaringClass(), colors.newInstance(false))),
                "colors: Near-black surfaces alone changes nothing");
        ProbeSupport.setting("amoled_near_black", "false");

        ProbeSupport.setting("amoled_black", "true");
        List<Long> on = longs(colors.getDeclaringClass(), colors.newInstance(false));
        ProbeSupport.setting("amoled_near_black", "true");
        List<Long> near = longs(colors.getDeclaringClass(), colors.newInstance(false));
        int replaced = 0;
        for (int i = 0; i < off.size(); i++) {
            if (off.get(i) == color(GREY)) {
                replaced++;
                ProbeSupport.check(on.get(i) == color(BLACK), "colors: the surface grey is black while on");
                ProbeSupport.check(near.get(i) == color(NEAR_BLACK), "colors: near-black with Near-black surfaces");
            } else {
                ProbeSupport.check(on.get(i).equals(off.get(i)) && near.get(i).equals(off.get(i)),
                        "colors: everything but the surface grey stays, field " + i);
            }
        }
        ProbeSupport.check(replaced > 0, "colors: no surface grey replaced");

        ProbeSupport.unregister("amoled_black");
        ProbeSupport.check(off.equals(longs(colors.getDeclaringClass(), colors.newInstance(false))),
                "colors: Rumble's while the patch isn't included");
    }

    /** Compose's Color(Long) for an sRGB ARGB value: the value in the upper 32 bits. */
    private static long color(long argb) {
        return (argb & 0xFFFFFFFFL) << 32;
    }

    /** The class of a recorded method reference ("Lpkg/A;->name(...)V"), without initializing it. */
    private static Class<?> owner(String reference) throws ClassNotFoundException {
        String descriptor = reference.substring(0, reference.indexOf("->"));
        return Class.forName(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'), false,
                ThemeProbe.class.getClassLoader());
    }

    /** The long fields of {@code type}: static ones when {@code instance} is null, else the instance's. */
    private static List<Long> longs(Class<?> type, Object instance) throws Exception {
        List<Long> values = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() != long.class || Modifier.isStatic(field.getModifiers()) != (instance == null)) continue;
            field.setAccessible(true);
            values.add(field.getLong(instance));
        }
        return values;
    }
}
