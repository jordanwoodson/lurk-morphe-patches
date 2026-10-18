package io.github.jordanwoodson.extension.shared.kick;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded data whitelist shared by the page receiver and the callable module. */
final class KickCatalog {
    private KickCatalog() { }
    static JSONObject sanitize(JSONObject data, String generation, Object channel, Object slug) {
        try {
            if (!generation.equals(data.optString("generation")) ||
                    !java.util.Objects.equals(channel, data.opt("channelId")) ||
                    !java.util.Objects.equals(slug, data.opt("slug"))) return null;
            JSONArray input = data.getJSONArray("emotes"), entries = new JSONArray();
            if (input.length() > 2000) return null;
            for (int i = 0; i < input.length(); i++) {
                JSONObject item = input.getJSONObject(i);
                String id = item.getString("id"), name = item.getString("name"), url = item.getString("url");
                if (!id.matches("(?i)([a-f0-9]{24}|[0-9A-HJKMNP-TV-Z]{26})") ||
                        !name.matches("[^\\s:<>&\"'\\\\]{1,100}") ||
                        !(url.equals("https://cdn.7tv.app/emote/" + id + "/2x.webp") ||
                          url.equals("https://cdn.7tv.app/emote/" + id + "/2x_static.webp"))) return null;
                int flags = item.getInt("flags"), width = item.getInt("width"), height = item.getInt("height");
                if (flags < 0 || flags > 1 || width < 1 || width > 512 || height < 1 || height > 512) return null;
                entries.put(new JSONObject().put("id", id).put("name", name).put("url", url)
                        .put("animated", item.optBoolean("animated", false)).put("flags", flags).put("width", width).put("height", height));
            }
            JSONArray sets = data.getJSONArray("setIds");
            if (sets.length() > 2) return null;
            for (int i = 0; i < sets.length(); i++) if (!sets.getString(i).matches("(?i)([a-f0-9]{24}|[0-9A-HJKMNP-TV-Z]{26})")) return null;
            JSONObject clean = new JSONObject().put("generation", generation)
                    .put("channelId", channel == null ? JSONObject.NULL : channel).put("emotes", entries);
            return clean;
        } catch (RuntimeException | JSONException e) { return null; }
    }
}
