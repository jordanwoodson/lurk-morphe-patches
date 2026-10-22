import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Disable analytics (with Keep watch progress), Block cookie notice and Clean share links: runs Rumble's
 * event repository, the Ketch entry points, the cached consent, the consent service's tracker start loop and
 * the extension's Airship feature filter and link cleaner, with the switches on and off.
 */
public final class PrivacyProbe {
    private static final String PRIVACY = "io.github.jordanwoodson.extension.shared.rumble.RumblePrivacy";

    public static void main(String[] args) {
        ProbeSupport.run("analytics uploads, watch progress, consent, Airship start and share links", PrivacyProbe::run);
    }

    private static void run() throws Exception {
        analytics();
        consent();
        shareLinks();
    }

    // ---------------------------------------------------------------------------------------------
    // Disable analytics

    private static void analytics() throws Exception {
        Class<?> repositoryType = ProbeSupport.type("rumble.disable_analytics.repository", "Lke/c;");
        Class<?> progressType = ProbeSupport.type("rumble.disable_analytics.watch_progress_event", "Lge/x;");
        Object repository = ProbeSupport.allocate(repositoryType);
        Method send = null, search = null, debug = null;
        for (Method m : repositoryType.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (p.length == 8 && p[0] == List.class && p[1] == String.class) send = m;
            else if (p.length == 5 && p[0] == String.class && p[2] == long.class) search = m;
            else if (p.length == 8 && p[6] == Map.class) debug = m;
        }
        ProbeSupport.check(send != null && search != null && debug != null, "event repository methods");
        Object progress = ProbeSupport.allocate(progressType);
        Object other = new Object();

        // On (the default), Keep watch progress off: Success for any batch, Unit for the rest.
        ProbeSupport.check("Success".equals(String.valueOf(upload(send, repository, Arrays.asList(progress, other)))),
                "events: Success without uploading");
        ProbeSupport.check("kotlin.Unit".equals(String.valueOf(ProbeSupport.outcome(search, repository,
                ProbeSupport.defaults(search)))), "search clicks: Unit");
        ProbeSupport.check("kotlin.Unit".equals(String.valueOf(ProbeSupport.outcome(debug, repository,
                ProbeSupport.defaults(debug)))), "debug log: Unit");

        // Keep watch progress: only the watch progress events go out.
        Method only = Class.forName(PRIVACY).getMethod("watchProgressOnly", List.class, Class.class);
        List<Object> mixed = Arrays.asList(other, progress, other, progress);
        ProbeSupport.check(only.invoke(null, mixed, progressType).equals(Arrays.asList(progress, progress)),
                "a mixed batch keeps its watch progress events only");
        List<Object> progressOnly = Arrays.asList(progress, progress);
        ProbeSupport.check(only.invoke(null, progressOnly, progressType) == progressOnly, "all progress: the same list");
        ProbeSupport.check(only.invoke(null, Arrays.asList(other), progressType) == null, "no progress: nothing");
        ProbeSupport.setting("keep_watch_progress", "true");
        ProbeSupport.check("Success".equals(String.valueOf(upload(send, repository, Arrays.asList(other)))),
                "kept: a batch without watch progress is still answered with Success");
        // With watch progress in the batch the upload goes on to Rumble's coroutine, which needs the
        // repository's dispatcher (null here).
        ProbeSupport.check(upload(send, repository, Arrays.asList(other, progress)) instanceof Throwable,
                "kept: a batch with watch progress is uploaded");

        ProbeSupport.setting("disable_analytics", "false");
        ProbeSupport.check(upload(send, repository, Arrays.asList(other)) instanceof Throwable, "off: every batch is uploaded");
        ProbeSupport.check(ProbeSupport.outcome(search, repository, ProbeSupport.defaults(search)) instanceof Throwable,
                "off: search clicks are sent");
        ProbeSupport.setting("disable_analytics", "true");
        ProbeSupport.setting("keep_watch_progress", "false");
    }

    private static Object upload(Method send, Object repository, List<?> events) throws Exception {
        Object[] args = ProbeSupport.defaults(send);
        args[0] = events;
        return ProbeSupport.outcome(send, repository, args);
    }

    // ---------------------------------------------------------------------------------------------
    // Block cookie notice

    private static void consent() throws Exception {
        // Ketch.load() and showPreferences(): false at once.
        Class<?> ketchType = ProbeSupport.type("rumble.block_cookie_notice.ketch", "Leb/a;");
        Object ketch = ProbeSupport.allocate(ketchType);
        List<Method> entries = new ArrayList<>();
        for (Method m : ketchType.getDeclaredMethods()) {
            if (m.getReturnType() == boolean.class && Arrays.equals(m.getParameterTypes(),
                    new Class<?>[]{boolean.class, boolean.class, int.class, int.class}) && !Modifier.isStatic(m.getModifiers())) {
                entries.add(m);
            }
        }
        ProbeSupport.check(entries.size() == 2, "Ketch load() and showPreferences()");
        for (Method entry : entries) {
            ProbeSupport.check(Boolean.FALSE.equals(ProbeSupport.outcome(entry, ketch, false, false, 0, 0)),
                    "Ketch " + entry.getName() + " returns false");
        }

        // The cached decision reads as none.
        Class<?> cachedType = ProbeSupport.type("rumble.block_cookie_notice.cached_consent", "LBd/a;");
        Object cached = cachedType.getConstructor(boolean.class, Map.class, String.class, String.class, String.class,
                Boolean.class).newInstance(true, Collections.singletonMap("analytics", true), "", "", "", null);
        Method hasDecision = ProbeSupport.bySignature(cachedType, boolean.class);
        ProbeSupport.check(!(boolean) hasDecision.invoke(cached), "a cached decision is ignored while on");

        // The start loop starts the Airship tracker only.
        Class<?> serviceType = ProbeSupport.type("rumble.block_cookie_notice.consent_service", "LCb/g;");
        Class<?> trackerType = ProbeSupport.type("rumble.block_cookie_notice.tracker", "Lib/b;");
        Class<?> airshipType = ProbeSupport.type("rumble.block_cookie_notice.airship_tracker", "LCb/b;");
        Method startLoop = ProbeSupport.method("rumble.block_cookie_notice.start_loop", "LCb/g;->F()V");
        List<String> started = new ArrayList<>();
        Object other = Proxy.newProxyInstance(trackerType.getClassLoader(), new Class<?>[]{trackerType},
                (proxy, m, a) -> {
                    switch (m.getName()) {
                        case "equals": return proxy == a[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "PrivacyProbe tracker";
                        default:
                            started.add(m.getName());
                            return null;
                    }
                });
        Object airship = ProbeSupport.allocate(airshipType);
        Field context = null;
        for (Field f : airshipType.getDeclaredFields()) {
            if (f.getType() == android.content.Context.class) context = f;
        }
        ProbeSupport.check(context != null, "the Airship tracker keeps a Context");
        context.setAccessible(true);
        // getApplicationContext() is the first thing Airship's start asks; not an Application, so it stops there.
        boolean[] airshipStarted = {false};
        context.set(airship, new android.content.ContextWrapper(null) {
            @Override
            public android.content.Context getApplicationContext() {
                airshipStarted[0] = true;
                return this;
            }
        });
        Object service = ProbeSupport.allocate(serviceType);
        Set<Object> trackers = new HashSet<>(Arrays.asList(other, airship));
        for (Field f : serviceType.getDeclaredFields()) {
            if (f.getType() == Set.class) {
                f.setAccessible(true);
                f.set(service, trackers);
            }
        }
        startLoop.invoke(service);
        ProbeSupport.check(airshipStarted[0], "the Airship tracker is started");
        ProbeSupport.check(started.isEmpty(), "no other tracker is started, got " + started);
        airshipStarted[0] = false;
        startLoop.invoke(service);
        ProbeSupport.check(!airshipStarted[0], "the start loop runs once");

        // Airship's features for essential_services lose its analytics (16).
        Class<?> featureType = ProbeSupport.type("rumble.block_cookie_notice.airship_feature", "Lcom/urbanairship/f$c;");
        List<Object> features = new ArrayList<>();
        Field value = null;
        for (Field f : featureType.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == featureType) {
                f.setAccessible(true);
                features.add(f.get(null));
            } else if (!Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                value = f;
            }
        }
        value.setAccessible(true);
        Object[] all = (Object[]) Array.newInstance(featureType, features.size());
        features.toArray(all);
        Method filter = Class.forName(PRIVACY).getMethod("airshipFeatures", Object[].class);
        Object[] kept = (Object[]) filter.invoke(null, (Object) all);
        ProbeSupport.check(kept.getClass() == all.getClass(), "the array keeps Airship's element type");
        int analytics = 0;
        for (Object feature : all) if (value.getInt(feature) == 16) analytics++;
        ProbeSupport.check(analytics >= 1 && kept.length == all.length - analytics, "analytics (16) is dropped");
        for (Object feature : kept) ProbeSupport.check(value.getInt(feature) != 16, "no analytics left");

        ProbeSupport.setting("block_cookie_notice", "false");
        ProbeSupport.check((boolean) hasDecision.invoke(cached), "off: the cached decision counts");
        ProbeSupport.check(filter.invoke(null, (Object) all) == all, "off: Airship gets Rumble's features");
        started.clear();
        Object stockService = ProbeSupport.allocate(serviceType);
        for (Field f : serviceType.getDeclaredFields()) {
            if (f.getType() == Set.class) {
                f.setAccessible(true);
                f.set(stockService, trackers);
            }
        }
        airshipStarted[0] = false;
        startLoop.invoke(stockService);
        ProbeSupport.check(started.isEmpty() && !airshipStarted[0], "off: without a decision Rumble starts no tracker");
        ProbeSupport.setting("block_cookie_notice", "true");
    }

    // ---------------------------------------------------------------------------------------------
    // Clean share links

    private static void shareLinks() throws Exception {
        Method clean = Class.forName(PRIVACY).getMethod("cleanShareLink", String.class);
        String[][] cases = {
                {"https://rumble.com/v6abc-title.html?e9s=src_v1_ucp", "https://rumble.com/v6abc-title.html"},
                {"https://www.rumble.com/shorts/v6abc?e9s=x#t=3", "https://www.rumble.com/shorts/v6abc"},
                {"https://RUMBLE.com/c/Bongino?x=1", "https://RUMBLE.com/c/Bongino"},
                {"https://notrumble.com/v6abc?e9s=x", "https://notrumble.com/v6abc?e9s=x"},
                {"https://example.com/?u=https://rumble.com/", "https://example.com/?u=https://rumble.com/"},
                {"https://rumble.com/v6abc", "https://rumble.com/v6abc"},
                {"not a link ? at all", "not a link ? at all"},
        };
        for (String[] c : cases) {
            ProbeSupport.check(c[1].equals(clean.invoke(null, c[0])), c[0] + " -> " + clean.invoke(null, c[0]));
        }
    }
}
