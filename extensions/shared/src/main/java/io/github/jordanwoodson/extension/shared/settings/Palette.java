package io.github.jordanwoodson.extension.shared.settings;

import android.content.res.ColorStateList;

/**
 * The settings screen's colors: Lurk's mint on navy. Dark unless the system is set to light; Twitch, Kick and
 * Rumble are dark apps. On light surfaces mint is too pale for text, so text and icons use a deep green there.
 */
final class Palette {
    static final int MINT = 0xFF39F8AE;
    static final int NAVY = 0xFF080D1B;

    final boolean dark;
    /** The window. */
    final int background;
    /** Cards: the header and each group of settings. */
    final int surface;
    /** The search field and pressed rows. */
    final int raised;
    final int divider;
    final int text;
    final int secondary;
    /** Mint where it can be read: section titles, values, icons, checked switches. */
    final int accent;
    /** The restart bar and buttons: mint fill with dark text. */
    final int fill;
    final int onFill;
    final int warning;
    final int warningSurface;
    final int ripple;

    private Palette(boolean dark) {
        this.dark = dark;
        if (dark) {
            background = NAVY;
            surface = 0xFF111A2E;
            raised = 0xFF1A2540;
            divider = 0xFF223050;
            text = 0xFFEEF2FA;
            secondary = 0xFF9DA9C2;
            accent = MINT;
            warning = 0xFFFFC266;
            warningSurface = 0xFF2B2210;
            ripple = 0x3339F8AE;
        } else {
            background = 0xFFF2F5FA;
            surface = 0xFFFFFFFF;
            raised = 0xFFE7ECF4;
            divider = 0xFFDDE3EE;
            text = 0xFF0B1220;
            secondary = 0xFF4C5A72;
            accent = 0xFF087A52;
            warning = 0xFF8A5300;
            warningSurface = 0xFFFFF1D6;
            ripple = 0x22087A52;
        }
        fill = MINT;
        onFill = 0xFF04140D;
    }

    static Palette of(boolean dark) {
        return new Palette(dark);
    }

    /** A switch's thumb: accent when on. */
    ColorStateList thumb() {
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}},
                new int[]{dark ? 0xFF4A5672 : 0xFFC3CAD6, accent, dark ? 0xFFC9D1E0 : 0xFFFFFFFF});
    }

    /** A switch's track: translucent accent when on. */
    ColorStateList track() {
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}},
                new int[]{dark ? 0x33FFFFFF : 0x22000000, (accent & 0x00FFFFFF) | 0x80000000,
                        dark ? 0x55FFFFFF : 0x40000000});
    }
}
