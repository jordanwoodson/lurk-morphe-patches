import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hide chat clutter, Show deleted messages and Chat timestamps: runs Rumble's live chat DTO getters
 * and the extension with a real message entity, with each switch on and off.
 */
public final class ChatProbe {
    private static final String CHAT = "io.github.jordanwoodson.extension.shared.rumble.RumbleChat";
    private static final String DTO = "com.rumble.network.dto.livechat.";

    public static void main(String[] args) {
        ProbeSupport.run("chat hook, badges, hidden chat, deleted text and timestamps", ChatProbe::run);
    }

    private static void run() throws Exception {
        // Live messages: one plain, one raid notice, one gift notice.
        Object plain = message(1, "hello", null, null);
        Object raid = message(2, "raid!", ProbeSupport.allocate(Class.forName(DTO + "RaidNotification")), null);
        Object gift = message(3, "gift!", null, ProbeSupport.allocate(Class.forName(DTO + "GiftPurchaseNotification")));
        List<Object> messages = new ArrayList<>(List.of(plain, raid, gift));
        Class<?> dataType = Class.forName(DTO + "LiveChatMessageData");
        Object data = ProbeSupport.allocate(dataType);
        ProbeSupport.set(data, "messages", messages);
        Method getter = ProbeSupport.method("rumble.chat.messages_getter",
                "Lcom/rumble/network/dto/livechat/LiveChatMessageData;->b()Ljava/util/List;");

        ProbeSupport.check(getter.invoke(data) == messages, "switches off: the same list");
        ProbeSupport.setting("chat_hide_raid_notices", "true");
        List<?> noRaids = (List<?>) getter.invoke(data);
        ProbeSupport.check(noRaids.equals(List.of(plain, gift)), "raid notices hidden: " + noRaids.size());
        ProbeSupport.check(getter.invoke(data) == noRaids, "the same filtered list on a second call");
        ProbeSupport.setting("chat_hide_gift_notices", "true");
        ProbeSupport.check(((List<?>) getter.invoke(data)).equals(List.of(plain)), "raid and gift notices hidden");

        // The join history goes through the same hook.
        Class<?> initType = Class.forName(DTO + "LiveChatInitData");
        Object init = ProbeSupport.allocate(initType);
        ProbeSupport.set(init, "messages", messages);
        Method history = ProbeSupport.method("rumble.chat.history_getter",
                "Lcom/rumble/network/dto/livechat/LiveChatInitData;->f()Ljava/util/List;");
        ProbeSupport.check(((List<?>) history.invoke(init)).equals(List.of(plain)), "history filtered");

        // A switch whose patch isn't included counts as off in the shared hook.
        ProbeSupport.unregister("chat_hide_raid_notices");
        ProbeSupport.unregister("chat_hide_gift_notices");
        ProbeSupport.check(getter.invoke(data) == messages, "unregistered keys hide nothing");

        // Badges and the whole chat.
        Class<?> userType = Class.forName(DTO + "LiveChatUser");
        Object user = ProbeSupport.allocate(userType);
        List<String> badges = List.of("verified");
        ProbeSupport.set(user, "badges", badges);
        Method badgesGetter = getterReading(userType, "badges");
        ProbeSupport.check(badgesGetter.invoke(user) == badges, "badges shown while off");
        ProbeSupport.setting("chat_hide_badges", "true");
        ProbeSupport.check(((List<?>) badgesGetter.invoke(user)).isEmpty(), "badges hidden while on");
        Class<?> videoType = Class.forName("com.rumble.network.dto.video.Video");
        Object video = ProbeSupport.allocate(videoType);
        Method chatDisabled = getterReading(videoType, "liveChatDisabled");
        ProbeSupport.check(chatDisabled.invoke(video) == null, "chat follows the server while off");
        ProbeSupport.setting("chat_hide_all", "true");
        ProbeSupport.check(Boolean.TRUE.equals(chatDisabled.invoke(video)), "chat disabled while on");

        // The message entity and its message and timeReceived fields, as the chat patches found them.
        Object entity = ProbeSupport.allocate(ProbeSupport.type("rumble.chat.entity", "LKe/l;"));
        ProbeSupport.set(entity, ProbeSupport.fieldName("rumble.chat.entity_message", "LKe/l;->f:Ljava/lang/String;"),
                "original text");
        ProbeSupport.set(entity, ProbeSupport.fieldName("rumble.chat.entity_time_received",
                "LKe/l;->k:Ljava/time/LocalDateTime;"), LocalDateTime.of(2026, 10, 16, 13, 5));
        Method deletedLabel = Class.forName(CHAT).getMethod("deletedLabel", String.class, Object.class);
        Method nameWithTime = Class.forName(CHAT).getMethod("nameWithTime", String.class, Object.class);
        String label = "Message was removed by moderator";
        ProbeSupport.check((label + ": original text").equals(deletedLabel.invoke(null, label, entity)),
                "deleted text shown (on by default), got " + deletedLabel.invoke(null, label, entity));
        // No Context under app_process, so the 12-hour format.
        ProbeSupport.check("1:05  alice".equals(nameWithTime.invoke(null, "alice", entity)),
                "timestamp shown (on by default), got " + nameWithTime.invoke(null, "alice", entity));
        ProbeSupport.setting("show_deleted_messages", "false");
        ProbeSupport.setting("chat_timestamps", "false");
        ProbeSupport.check(label.equals(deletedLabel.invoke(null, label, entity)), "Rumble's label while off");
        ProbeSupport.check("alice".equals(nameWithTime.invoke(null, "alice", entity)), "Rumble's name while off");
    }

    private static Object message(long id, String text, Object raid, Object gift) throws Exception {
        Object message = ProbeSupport.allocate(Class.forName(DTO + "LiveChatMessage"));
        ProbeSupport.set(message, "id", id);
        ProbeSupport.set(message, "text", text);
        ProbeSupport.set(message, "raidNotification", raid);
        ProbeSupport.set(message, "giftPurchaseNotification", gift);
        return message;
    }

    /** The getter of a DTO field: the no-argument method returning the value set in the field. */
    private static Method getterReading(Class<?> type, String field) throws Exception {
        Object probe = ProbeSupport.allocate(type);
        Object marker = field.equals("badges") ? new ArrayList<>(Collections.singletonList("x")) : Boolean.FALSE;
        ProbeSupport.set(probe, field, marker);
        for (Method method : type.getDeclaredMethods()) {
            if (method.getParameterCount() != 0 || method.getReturnType() != ProbeSupport.field(type, field).getType()) {
                continue;
            }
            if (method.getName().equals("hashCode") || method.getName().equals("toString")) continue;
            try {
                if (method.invoke(probe) == marker) return method;
            } catch (Exception ignored) {
                // Not the getter.
            }
        }
        throw new AssertionError("no getter for " + type.getName() + "." + field);
    }
}
