import org.json.JSONObject;
import java.lang.reflect.Method;

/** Runs the production catalog validator on ART; no React Native/account/network simulation. */
public final class CatalogProbe {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("io.github.jordanwoodson.extension.shared.kick.KickCatalog");
        Method sanitize = type.getDeclaredMethod("sanitize", JSONObject.class, String.class, Object.class, Object.class);
        sanitize.setAccessible(true);
        String valid = "{\"generation\":\"one\",\"channelId\":\"668\",\"slug\":\"xqc\",\"setIds\":[],\"emotes\":[{\"id\":\"333333333333333333333333\",\"name\":\"EZ\",\"url\":\"https://cdn.7tv.app/emote/333333333333333333333333/2x.webp\",\"animated\":true,\"flags\":0,\"width\":64,\"height\":64,\"credentials\":\"must-not-forward\"}],\"draft\":\"must-not-forward\"}";
        Object clean = sanitize.invoke(null, new JSONObject(valid), "one", "668", "xqc");
        if (clean == null || clean.toString().contains("must-not-forward")) throw new AssertionError("valid snapshot or whitelist");
        if (sanitize.invoke(null, new JSONObject(valid.replace("/2x.webp", "/2x_static.webp")), "one", "668", "xqc") == null) throw new AssertionError("static WebP rejected");
        if (!((JSONObject) clean).getJSONArray("emotes").getJSONObject(0).getBoolean("animated")) throw new AssertionError("animated metadata lost");
        String[][] bad = {{"\"one\"","\"old\""},{"\"668\"","\"999\""},{"\"xqc\"","\"other\""},
                {"https://cdn.7tv.app/","https://evil.example/"},{"\"EZ\"","\"bad word\""},{"\"width\":64","\"width\":999999"}};
        for (String[] change : bad) if (sanitize.invoke(null, new JSONObject(valid.replace(change[0],change[1])),"one","668","xqc") != null)
            throw new AssertionError("accepted " + change[1]);
        System.out.println("PASS: native catalog identity, URL/size/name validation and data whitelist");
    }
}
