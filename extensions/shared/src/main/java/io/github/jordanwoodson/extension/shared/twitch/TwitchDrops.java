package io.github.jordanwoodson.extension.shared.twitch;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * Auto-claim Drops: when Twitch's drops notification in a channel shows a drop as claimable, its Claim button is
 * pressed for you, through the same handler a tap goes to (Twitch's claim mutation with your account). Each drop
 * instance is claimed once per app start.
 */
@SuppressWarnings("unused")
public final class TwitchDrops {
    static final String KEY = "drops_auto_claim";
    private static final int MAX_CLAIMED = 256;

    /** Drop instance IDs already claimed in this process. */
    private static final Set<String> CLAIMED = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_CLAIMED;
                }
            }));
    /** The last claim that threw, for logs and the probe. */
    static volatile Throwable lastFailure;
    /** How many claims were handed to Twitch's handler, for the probe. */
    static volatile int claims;

    private TwitchDrops() {
    }

    /**
     * Injection point: the drops notification presenter just showed a drop presentation. When its drop is
     * claimable, presses its Claim button, once per drop instance, while the switch is on.
     */
    public static void presented(Object presenter, Object presentation) {
        if (presenter == null || presentation == null || !Settings.isEnabled(KEY)) return;
        Object claimable;
        String id;
        try {
            claimable = claimableOf(presentation);
            if (claimable == null) return;
            id = dropInstanceId(claimable);
        } catch (RuntimeException e) {
            Logger.d("drops: no drop instance: " + e);
            return;
        }
        if (id == null || id.isEmpty() || !CLAIMED.add(id)) return;
        try {
            claims++;
            tapClaim(presenter, claimable);
            Logger.d("drops: claiming " + id);
        } catch (RuntimeException e) {
            lastFailure = e;
            Logger.d("drops: claim failed: " + e);
        }
    }

    /** Replaced at patch time: the presentation's drop when it is a claimable one, else null. */
    private static Object claimableOf(Object presentation) {
        return null;
    }

    /** Replaced at patch time: the claimable drop's instance ID, the one the claim mutation takes. */
    private static String dropInstanceId(Object claimable) {
        return null;
    }

    /** Replaced at patch time: the presenter's view-event handler, given the Claim button's event for the drop. */
    private static void tapClaim(Object presenter, Object claimable) {
    }
}
