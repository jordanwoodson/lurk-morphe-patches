import android.content.SharedPreferences;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

/**
 * Default startup tab and Hide Home sections: runs Rumble's startup tab getter (with a fake
 * SharedPreferences holding My Feed) and the Home section kind getter, with each setting on and off.
 */
public final class InterfaceProbe {
    public static void main(String[] args) {
        ProbeSupport.run("startup tab choice and Home section kinds", InterfaceProbe::run);
    }

    private static void run() throws Exception {
        // The rumble_prefs helper's startup tab getter the patch matched. Its SharedPreferences and key fields are
        // found by type: every String field gets the key.
        Method tab = ProbeSupport.method("rumble.startup_tab.getter", "Lzd/d;->a()I");
        Object helper = ProbeSupport.allocate(tab.getDeclaringClass());
        SharedPreferences stored = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, values) -> method.getName().equals("getInt") ? 3 : null);
        for (Field field : helper.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            if (field.getType() == SharedPreferences.class) field.set(helper, stored);
            else if (field.getType() == String.class) field.set(helper, "defaultTabIndexKey");
        }
        ProbeSupport.check((int) tab.invoke(helper) == 3, "Rumble's choice (default): the stored My Feed");
        ProbeSupport.setting("startup_tab", "0");
        ProbeSupport.check((int) tab.invoke(helper) == 0, "Home while chosen");

        Class<?> sectionType = Class.forName("com.rumble.network.dto.home.HomeSectionDto");
        Method kind = ProbeSupport.method("rumble.hide_home_sections.kind_getter",
                "Lcom/rumble/network/dto/home/HomeSectionDto;->f()Ljava/lang/String;");
        Object creators = section(sectionType, "creators", "Top Channels");
        Object editors = section(sectionType, "videos", "Editor Picks");
        ProbeSupport.check("creators".equals(kind.invoke(creators)), "creator rows shown while off");
        ProbeSupport.check("videos".equals(kind.invoke(editors)), "titled sections shown while nothing is listed");
        ProbeSupport.setting("home_hide_creators", "true");
        ProbeSupport.setting("home_hide_titles", " editor picks , Live");
        ProbeSupport.check("hide".equals(kind.invoke(creators)), "creator rows hidden while on");
        ProbeSupport.check("hide".equals(kind.invoke(editors)), "a listed title is hidden, in any case");
        ProbeSupport.check("videos".equals(kind.invoke(section(sectionType, "videos", "News"))), "others stay");
    }

    private static Object section(Class<?> type, String kind, String title) throws Exception {
        Object section = ProbeSupport.allocate(type);
        ProbeSupport.set(section, "kind", kind);
        ProbeSupport.set(section, "title", title);
        return section;
    }
}
