package io.github.jordanwoodson.patches.twitch.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall

internal object BundleLoaderFingerprint : Fingerprint(
    name = "loadScriptFromAssets", returnType = "V",
    parameters = listOf("Landroid/content/res/AssetManager;", "Ljava/lang/String;", "Z"),
    filters = listOf(methodCall(
        definingClass = "Lcom/facebook/react/runtime/ReactInstance;",
        name = "access\$loadJSBundleFromAssets",
    )),
)
