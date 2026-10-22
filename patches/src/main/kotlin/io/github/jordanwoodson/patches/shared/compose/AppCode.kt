package io.github.jordanwoodson.patches.shared.compose

import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException

/**
 * The app a lookup in this package works on: its name for error messages and the newest version its patches
 * target, both taken from the app's Compatibility so no version is written out by hand.
 */
internal class AppCode(compatibility: Compatibility) {
    val name: String = compatibility.name ?: compatibility.packageName ?: "app"
    val version: String = compatibility.targets.maxOrNull()?.version
        ?: throw IllegalArgumentException("${compatibility.name} has no target version")

    /** A PatchException starting with the app's name. */
    fun error(message: String) = PatchException("$name: $message")

    /** The sentence lookups add when they find nothing: which version the patches were written for. */
    val recheck: String
        get() = "These patches target $name $version; recheck the code before updating compatibility."
}
