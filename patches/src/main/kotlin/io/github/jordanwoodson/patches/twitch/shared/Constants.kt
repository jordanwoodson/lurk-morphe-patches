package io.github.jordanwoodson.patches.twitch.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_TWITCH = Compatibility(
        name = "Twitch",
        packageName = "tv.twitch.android.app",
        // APKMirror ships it as a bundle. Manager only links to APKMirror/Uptodown when this matches.
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x9146FF,
        // Original signing cert (apksigner "certificate SHA-256 digest"). bin/apk-add pins it.
        signatures = setOf("1338f9b049893cc70b78432a177582f90bd4bc6296ea4ed35bcc7df59687ac53"),
        targets = listOf(
            // Latest stable on APKMirror on 2026-10-21. Update to the version you develop against.
            AppTarget(version = "31.7.0"),
        ),
    )
}
