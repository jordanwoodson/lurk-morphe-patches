import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Block update prompts: calls Rumble's forced and suggested update checks with null dependencies.
 * Patched with the switch on, both return false before reaching Firebase. With the switch off (and on the
 * stock APK) the original code runs: Firebase isn't set up under app_process, and the catch block calls
 * the null error use case, so the call throws.
 */
public final class UpdateProbe {
    public static void main(String[] args) {
        ProbeSupport.run("update checks return false with the switch on and run Rumble's code with it off", () -> {
            // ShouldForceNewAppVersionUseCase and ShouldSuggestNewAppVersionUseCase, as the patch matched them.
            Method forceCheck = ProbeSupport.method("rumble.block_update_prompts.force_check",
                    "LFe/l;->a(Ljava/lang/String;)Z");
            Method suggestCheck = ProbeSupport.method("rumble.block_update_prompts.suggest_check",
                    "LFe/m;->a(Ljava/lang/String;LQh/c;)Ljava/lang/Object;");
            Object forceCase = ProbeSupport.allocate(forceCheck.getDeclaringClass());
            Object suggestCase = ProbeSupport.allocate(suggestCheck.getDeclaringClass());

            ProbeSupport.check(Boolean.FALSE.equals(forceCheck.invoke(forceCase, "0.0.1")),
                    "forced update check must return false with the switch on (its default)");
            ProbeSupport.check(Boolean.FALSE.equals(suggestCheck.invoke(suggestCase, "0.0.1", null)),
                    "suggested update check must return Boolean.FALSE with the switch on");

            ProbeSupport.setting("block_update_prompts", "false");
            ProbeSupport.check(throwsOriginal(forceCheck, forceCase, "0.0.1"),
                    "forced update check must run Rumble's code with the switch off");
            ProbeSupport.check(throwsOriginal(suggestCheck, suggestCase, "0.0.1", null),
                    "suggested update check must run Rumble's code with the switch off");
        });
    }

    private static boolean throwsOriginal(Method method, Object target, Object... args) throws Exception {
        try {
            method.invoke(target, args);
            return false;
        } catch (InvocationTargetException e) {
            return true;
        }
    }
}
