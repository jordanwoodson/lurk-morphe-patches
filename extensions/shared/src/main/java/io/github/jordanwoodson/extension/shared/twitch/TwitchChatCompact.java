package io.github.jordanwoodson.extension.shared.twitch;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.view.View;
import android.widget.TextView;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.github.jordanwoodson.extension.shared.Logger;
import io.github.jordanwoodson.extension.shared.settings.Settings;

/**
 * Native chat lines without badges, with emotes as their text, and with a chatter's first message in the channel
 * tinted. Each is its own switch; everything here leaves chat as Twitch draws it while its switch is off.
 */
@SuppressWarnings("unused")
public final class TwitchChatCompact {
    static final String HIDE_BADGES = "chat_hide_badges";
    static final String TEXT_EMOTES = "chat_text_emotes";
    static final String FIRST_CHATTERS = "chat_first_chatters";

    /** Green at 30%, apart from Chat keywords' purple; readable over the dark and the light chat background. */
    static final int FIRST_COLOR = 0x4D00C853;
    private static final int MAX_FIRST = 512;

    /** The marker on a first message's sender span. Spans of any class survive Twitch's copies of the line. */
    private static final Object FIRST = new Object();

    /** Message IDs Twitch's chat server marked as the sender's first message in the channel (newest last). */
    private static final Set<String> FIRST_IDS = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_FIRST;
                }
            }));
    /** The message ID of the line being built on this thread. */
    private static final ThreadLocal<String> LINE = new ThreadLocal<>();

    private TwitchChatCompact() {
    }

    /** Injection point: a chat line's badge span, right before the line's spans are put together. */
    public static CharSequence badges(CharSequence badges) {
        return badges != null && badges.length() > 0 && Settings.isEnabled(HIDE_BADGES) ? "" : badges;
    }

    // ---------------------------------------------------------------------------------------------
    // First-time chatters

    /**
     * Injection point: native chat parsed an IRC line's tags. Twitch's chat server tags a chatter's first message
     * in the channel {@code first-msg=1}; its {@code id} is kept so the line can be tinted when it's built.
     */
    public static void ircTags(Map<?, ?> tags) {
        if (tags == null || tags.isEmpty()) return;
        try {
            if (!"1".equals(tags.get("first-msg")) || !Settings.isEnabled(FIRST_CHATTERS)) return;
            Object id = tags.get("id");
            if (id instanceof String && !((String) id).isEmpty()) {
                FIRST_IDS.add((String) id);
                Logger.d("chat: first message " + id);
            }
        } catch (RuntimeException e) {
            Logger.d("chat: tags failed: " + e);
        }
    }

    /** Injection point: the start of the chat line builder, with the line's message ID. */
    public static void beginLine(String messageId) {
        LINE.set(messageId);
    }

    /** Injection point: the end of the chat line builder. Returns the sender spans, marked for a first message. */
    public static CharSequence markLine(CharSequence sender) {
        String id = LINE.get();
        LINE.remove();
        if (id == null || sender == null || sender.length() == 0 || !FIRST_IDS.contains(id)) return sender;
        if (!Settings.isEnabled(FIRST_CHATTERS)) return sender;
        Spannable marked = sender instanceof Spannable ? (Spannable) sender : new SpannableString(sender);
        marked.setSpan(FIRST, 0, marked.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return marked;
    }

    /** Injection point: a chat row once bound. Tints a first message's row unless Twitch shows it selected. */
    public static void bindRow(View row, TextView text) {
        try {
            if (row == null || text == null || !Settings.isEnabled(FIRST_CHATTERS)) return;
            CharSequence line = text.getText();
            if (!(line instanceof Spanned) || ((Spanned) line).getSpanStart(FIRST) < 0) return;
            if (TwitchChatStyle.isStockBackground(row)) row.setBackgroundColor(FIRST_COLOR);
        } catch (RuntimeException e) {
            Logger.d("chat: first message row failed: " + e);
        }
    }
}
