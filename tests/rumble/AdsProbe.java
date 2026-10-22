import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Block ads: runs the real ad repository's fetches and reports, the server's video list getters with a
 * Boosted video in them, Video.boosted and the Home section kind parser, with the switch on and off.
 */
public final class AdsProbe {
    private static final String DTO = "com.rumble.network.dto.";

    public static void main(String[] args) {
        ProbeSupport.run("ad answers, Boosted videos and Home ad sections, on and off", AdsProbe::run);
    }

    private static void run() throws Exception {
        // The ad repository (Rumble's one implementation of the interface the video ad use case takes).
        Class<?> repositoryType = ProbeSupport.type("rumble.block_ads.repository", "LKf/b;");
        Object repository = ProbeSupport.allocate(repositoryType);
        Method videoAds = null, impression = null, events = null;
        List<Method> displayAds = new ArrayList<>();
        for (Method m : repositoryType.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 8 && p[0] == String.class && p[4] == Long.class && p[5] == float.class) videoAds = m;
            else if (p.length >= 15) displayAds.add(m);
            else if (p.length == 2 && p[0] == String.class && m.getReturnType() == Object.class) impression = m;
            else if (p.length == 3 && p[0] == List.class && p[1] == long.class) events = m;
        }
        ProbeSupport.check(videoAds != null && impression != null && events != null && displayAds.size() == 2,
                "ad repository methods");

        // On (the default): Rumble's own "no ad" answers, without touching the repository's (null) fields.
        ProbeSupport.check("EmptyVideoAdList".equals(String.valueOf(call(videoAds, repository))), "video ads: none");
        for (Method display : displayAds) {
            ProbeSupport.check("WaitingForAds".equals(String.valueOf(call(display, repository))), "display ads: waiting");
        }
        ProbeSupport.check("kotlin.Unit".equals(String.valueOf(call(impression, repository))), "impressions: Unit");
        ProbeSupport.check(call(events, repository) != null, "ad events: success");

        // Boosted videos: Video and its Boosted are Gson DTOs, which keep their names.
        Class<?> videoType = Class.forName(DTO + "video.Video");
        Class<?> boostedType = Class.forName(DTO + "video.Boosted");
        Object plain = ProbeSupport.allocate(videoType);
        Object boosted = ProbeSupport.allocate(videoType);
        ProbeSupport.set(boosted, "boosted", ProbeSupport.allocate(boostedType));
        List<Object> videos = Arrays.asList(plain, boosted, plain);
        String[][] lists = {
                {"home.HomeSectionDto", "videos"}, {"video.RelatedVideoData", "videos"}, {"video.Video", "related"},
                {"search.VideoItems", "items"}, {"discover.CategoryVideoListData", "items"},
        };
        for (String[] list : lists) {
            Object dto = ProbeSupport.allocate(Class.forName(DTO + list[0]));
            ProbeSupport.set(dto, list[1], videos);
            ProbeSupport.check(listGetter(dto).equals(Arrays.asList(plain, plain)), list[0] + "." + list[1] + " drops Boosted");
        }
        Object only = ProbeSupport.allocate(Class.forName(DTO + "home.HomeSectionDto"));
        List<Object> clean = Arrays.asList(plain, plain);
        ProbeSupport.set(only, "videos", clean);
        ProbeSupport.check(listGetter(only) == clean, "a list without Boosted videos is Rumble's own");
        Method boostedGetter = ProbeSupport.bySignature(videoType, boostedType);
        ProbeSupport.check(boostedGetter.invoke(boosted) == null, "Video.boosted is null while on");

        // Home's ad_block sections parse as HIDE.
        Class<?> kindType = Class.forName(DTO + "home.HomeSectionKind");
        Object companion = kindType.getField("Companion").get(null);
        Method parse = ProbeSupport.bySignature(companion.getClass(), kindType, String.class);
        ProbeSupport.check("HIDE".equals(ProbeSupport.name(parse.invoke(companion, "ad_block"))), "ad_block is hidden");
        ProbeSupport.check("VIDEOS".equals(ProbeSupport.name(parse.invoke(companion, "videos"))), "videos stay");

        // Off: Rumble's code runs.
        ProbeSupport.setting("block_ads", "false");
        ProbeSupport.check(call(videoAds, repository) instanceof Throwable, "off: the video ad fetch runs Rumble's code");
        ProbeSupport.check(call(impression, repository) instanceof Throwable, "off: the impression report runs");
        Object section = ProbeSupport.allocate(Class.forName(DTO + "home.HomeSectionDto"));
        ProbeSupport.set(section, "videos", videos);
        ProbeSupport.check(listGetter(section) == videos, "off: Boosted videos stay");
        ProbeSupport.check(boostedGetter.invoke(boosted) != null, "off: Video.boosted is the server's");
        ProbeSupport.check("AD_BLOCK".equals(ProbeSupport.name(parse.invoke(companion, "ad_block"))), "off: ad_block stays");
    }

    /** The result, or the exception Rumble's code threw (the repository has no dependencies here). */
    private static Object call(Method m, Object target) throws Exception {
        return ProbeSupport.outcome(m, target, ProbeSupport.defaults(m));
    }

    /** What the DTO's list getter returns: the one no-argument List getter that isn't null here. */
    private static List<?> listGetter(Object dto) throws Exception {
        List<?> found = null;
        for (Method m : dto.getClass().getDeclaredMethods()) {
            if (m.getParameterTypes().length != 0 || m.getReturnType() != List.class || Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Object value = m.invoke(dto);
            if (value == null) continue;
            if (found != null) throw new AssertionError("several lists set in " + dto.getClass());
            found = (List<?>) value;
        }
        if (found == null) throw new AssertionError("no list getter in " + dto.getClass());
        return found;
    }
}
