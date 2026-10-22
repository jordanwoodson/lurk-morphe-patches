import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Don't save searches: calls Rumble's save, update and recent-searches use cases with no repository.
 * With the switch on (its default) they return before reaching it; off, Rumble's code runs and fails on the
 * missing repository.
 */
public final class SearchProbe {
    public static void main(String[] args) {
        ProbeSupport.run("search history use cases skip the database while on", () -> {
            // The save, update and recent-searches use cases the patch matched.
            Method saveCall = ProbeSupport.method("rumble.no_search_history.save",
                    "LMf/j;->a(Ljava/lang/String;LQh/c;)Ljava/lang/Object;");
            Method updateCall = ProbeSupport.method("rumble.no_search_history.update",
                    "LMf/m;->a(LLf/d;LQh/c;)Ljava/lang/Object;");
            Method recentCall = ProbeSupport.method("rumble.no_search_history.recent", "LMf/h;->a(LQh/c;)Ljava/lang/Object;");
            Object save = ProbeSupport.allocate(saveCall.getDeclaringClass());
            Object update = ProbeSupport.allocate(updateCall.getDeclaringClass());
            Object recent = ProbeSupport.allocate(recentCall.getDeclaringClass());

            Class<?> unitType = Class.forName("kotlin.Unit");
            Object unit = ProbeSupport.staticOfType(unitType, unitType);
            ProbeSupport.check(saveCall.invoke(save, "a search", null) == unit, "saving returns Unit while on");
            ProbeSupport.check(updateCall.invoke(update, null, null) == unit, "updating returns Unit while on");
            Object list = recentCall.invoke(recent, (Object) null);
            ProbeSupport.check(list instanceof List && ((List<?>) list).isEmpty(), "no recent searches while on");

            ProbeSupport.setting("no_search_history", "false");
            ProbeSupport.check(throwsOriginal(saveCall, save, "a search", null), "saving reaches the repository while off");
            ProbeSupport.check(throwsOriginal(recentCall, recent, (Object) null), "recent searches read the repository while off");
        });
    }

    private static boolean throwsOriginal(Method method, Object target, Object... args) throws Exception {
        try {
            method.invoke(target, args);
            return false;
        } catch (InvocationTargetException e) {
            return e.getCause() instanceof NullPointerException;
        }
    }
}
