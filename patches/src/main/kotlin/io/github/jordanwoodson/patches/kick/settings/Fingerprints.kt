package io.github.jordanwoodson.patches.kick.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

internal object BundleLoaderFingerprint : Fingerprint(
    name = "loadScriptFromAssets", returnType = "V",
    parameters = listOf("Landroid/content/res/AssetManager;", "Ljava/lang/String;", "Z"),
    filters = listOf(string("assetManager"), string("assetURL"), methodCall(
        definingClass = "Lcom/facebook/react/runtime/ReactInstance;",
        name = "access\$loadJSBundleFromAssets",
    )),
)
