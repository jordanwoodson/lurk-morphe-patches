package io.github.jordanwoodson.extension.shared.rumble;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import io.github.jordanwoodson.extension.shared.ChatFilter;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/** Interface settings for Rumble. With a setting off, each hook returns Rumble's own value. */
@SuppressWarnings("unused")
public final class RumbleInterface {
    private static Locale countLocale;
    private static NumberFormat countFormat;

    private RumbleInterface() {
    }

    // ---------------------------------------------------------------------------------------------
    // Show exact counts

    /** Injection point: a count in full, grouped the device locale's way (12,345). */
    public static synchronized String exactCount(long count) {
        Locale locale = Locale.getDefault();
        if (countFormat == null || !locale.equals(countLocale)) {
            countFormat = NumberFormat.getIntegerInstance(locale);
            countLocale = locale;
        }
        return countFormat.format(count);
    }

    // ---------------------------------------------------------------------------------------------
    // Channel action buttons (Hide tipping and Rants, Hide Join and Shop buttons)

    static final String HIDE_TIPPING = "hide_tipping";
    static final String HIDE_JOIN = "hide_join_button";
    static final String HIDE_SHOP = "hide_shop_button";

    /** The last lists handed out, so a composable drawn again with the same list gets the same list back. */
    private static final int REMEMBERED = 8;
    private static final Object[][] BUTTONS = new Object[REMEMBERED][];
    private static int nextButtons;

    /** Whether a switch of an included patch is on. Keys of patches left out count as off here. */
    static boolean on(String key) {
        return Settings.isRegistered(key) && Settings.isEnabled(key);
    }

    /**
     * Injection point: the start of the composable that draws a channel's or video's action buttons, with the
     * list of buttons it may draw and Rumble's Tip, Join and Shop constants. Returns the list without the
     * buttons whose switch is on: the same list while none is, and the same filtered list as last time for the
     * same list, so Compose can still skip the buttons when nothing changed.
     */
    public static List<?> actionButtons(List<?> buttons, Object tip, Object join, Object shop) {
        if (buttons == null || buttons.isEmpty()) return buttons;
        boolean hideTip = tip != null && on(HIDE_TIPPING);
        boolean hideJoin = join != null && on(HIDE_JOIN);
        boolean hideShop = shop != null && on(HIDE_SHOP);
        if (!hideTip && !hideJoin && !hideShop) return buttons;
        int settings = (hideTip ? 1 : 0) | (hideJoin ? 2 : 0) | (hideShop ? 4 : 0);
        synchronized (BUTTONS) {
            for (Object[] remembered : BUTTONS) {
                if (remembered != null && ((WeakReference<?>) remembered[0]).get() == buttons
                        && (Integer) remembered[1] == settings) {
                    return (List<?>) remembered[2];
                }
            }
        }
        List<Object> kept = new ArrayList<>(buttons.size());
        for (Object button : buttons) {
            if ((hideTip && button == tip) || (hideJoin && button == join) || (hideShop && button == shop)) continue;
            kept.add(button);
        }
        List<?> result = kept.size() == buttons.size() ? buttons : Collections.unmodifiableList(kept);
        synchronized (BUTTONS) {
            BUTTONS[nextButtons] = new Object[]{new WeakReference<>(buttons), settings, result};
            nextButtons = (nextButtons + 1) % REMEMBERED;
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------------
    // Hide Home sections

    private static volatile Field titleField;

    /**
     * Injection point: the kind of a Home section from the server (HomeSectionDto.kind). "hide" for a row of
     * categories, creators or playlists whose switch is on, or a section whose title is listed.
     */
    public static String homeSectionKind(Object section, String kind) {
        if (kind == null) return null;
        if (("categories".equals(kind) && Settings.isEnabled("home_hide_categories"))
                || ("creators".equals(kind) && Settings.isEnabled("home_hide_creators"))
                || ("playlists".equals(kind) && Settings.isEnabled("home_hide_playlists"))) {
            return "hide";
        }
        String titles = Settings.getText("home_hide_titles");
        if (titles.trim().isEmpty() || section == null) return kind;
        try {
            Field field = titleField;
            if (field == null) {
                field = section.getClass().getDeclaredField("title");
                field.setAccessible(true);
                titleField = field;
            }
            Object title = field.get(section);
            if (!(title instanceof String)) return kind;
            String wanted = ((String) title).trim();
            for (String entry : ChatFilter.entries(titles)) {
                if (entry.equalsIgnoreCase(wanted)) return "hide";
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return kind;
        }
        return kind;
    }
}
