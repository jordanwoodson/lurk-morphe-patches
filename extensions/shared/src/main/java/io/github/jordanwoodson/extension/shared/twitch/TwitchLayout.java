package io.github.jordanwoodson.extension.shared.twitch;

import android.app.Application;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.reflect.Method;

import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/** Layout choices for Twitch's main screen and player backend. Each one keeps Twitch's behaviour while it's off. */
@SuppressWarnings("unused")
public final class TwitchLayout {
    private static final String TOP_LAYOUT = "lurk_viewer_landing_top";
    private static final String TOP_TAG = "lurk_top";
    /** Material's NavigationBarView.LABEL_VISIBILITY_UNLABELED. */
    private static final int UNLABELED = 2;

    private TwitchLayout() {
    }

    // ---------------------------------------------------------------------------------------------
    // Tablet layout

    /**
     * Injection point: each read of Twitch's isTablet and is_tablet resources, true on tablets (smallest
     * width 600 dp). isTablet makes Twitch's device form factor Tablet.
     */
    public static boolean tablet(boolean stock) {
        return stock || Settings.isEnabled("tablet_layout");
    }

    /**
     * Injection point: each read of Twitch's following_drawer_use_sidebar resource, true on screens 1024 dp
     * wide: the navigation rail instead of the bottom bar, with the Following sidebar next to it. Twitch reads
     * it again on every rotation. A phone held upright only gets it when asked for: signed in, Twitch opens
     * its 320 dp sidebar next to the rail, which leaves little of the screen.
     */
    public static boolean navigationRail(boolean stock) {
        if (stock || !Settings.isEnabled("tablet_layout")) return stock;
        if (Settings.isEnabled("tablet_rail_portrait")) return true;
        try {
            return TwitchContext.application().getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Navigation bar

    /** 0 bottom, 1 bottom with icons only, 2 top, 3 top with icons only. */
    private static int navigationBar() {
        return Settings.getInt("navigation_bar");
    }

    /** Injection point: the main screen's layout, replaced by the copy with the bar at the top. */
    public static int landingLayout(int stock) {
        int choice = navigationBar();
        if (choice != 2 && choice != 3) return stock;
        try {
            Resources resources = TwitchContext.application().getResources();
            int top = resources.getIdentifier(TOP_LAYOUT, "layout", resources.getResourcePackageName(stock));
            if (top != 0) return top;
            Logger.d("navigation bar: no " + TOP_LAYOUT);
        } catch (RuntimeException e) {
            Logger.d("navigation bar: " + e);
        }
        return stock;
    }

    /**
     * Injection point: the end of the main screen's insets listener, which has just given the bar's group
     * ({@code group}) a bottom margin for the gesture or navigation bar.
     */
    public static void landingInsets(View group) {
        if (group == null) return;
        ViewParent parent = group.getParent();
        if (TOP_TAG.equals(group.getTag()) && parent instanceof View) {
            // The group is anchored to the top, where that margin does nothing: the screen gets the inset.
            ViewGroup.LayoutParams params = group.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams) {
                View layout = (View) parent;
                int bottom = ((ViewGroup.MarginLayoutParams) params).bottomMargin;
                if (layout.getPaddingBottom() != bottom) {
                    layout.setPadding(layout.getPaddingLeft(), layout.getPaddingTop(), layout.getPaddingRight(), bottom);
                }
            }
        }
        int choice = navigationBar();
        if (choice == 1 || choice == 3) {
            // The bars in the group (viewer, creator) and the navigation rail next to it.
            hideLabels(group);
            if (parent instanceof ViewGroup) hideLabels((ViewGroup) parent);
        }
    }

    private static void hideLabels(View container) {
        if (!(container instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) container;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            try {
                Method setter = child.getClass().getMethod("setLabelVisibilityMode", int.class);
                Method getter = child.getClass().getMethod("getLabelVisibilityMode");
                if (!Integer.valueOf(UNLABELED).equals(getter.invoke(child))) setter.invoke(child, UNLABELED);
            } catch (NoSuchMethodException notABar) {
                // Not a navigation bar.
            } catch (ReflectiveOperationException | RuntimeException e) {
                Logger.d("navigation bar labels: " + e);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Force ExoPlayer

    /**
     * Injection point: the start of the player configuration's constructor. {@code format} is the content
     * format: HLS for live channels and videos, MP4 for clips and feed videos. Twitch's ExoPlayer backend
     * plays HLS manifests but not clip manifests, so only HLS is moved to it.
     */
    public static boolean forceExoPlayer(boolean stock, Enum<?> format) {
        if (stock || format == null || !"HLS".equals(format.name())) return stock;
        if (!Settings.isEnabled("force_exoplayer")) return false;
        Logger.d("player: ExoPlayer for " + format.name());
        return true;
    }

    // ---------------------------------------------------------------------------------------------
}
