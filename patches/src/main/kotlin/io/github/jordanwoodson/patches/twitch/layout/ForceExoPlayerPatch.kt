package io.github.jordanwoodson.patches.twitch.layout

import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

@Suppress("unused")
val forceExoPlayerPatch = bytecodePatch(
    name = "Force ExoPlayer",
    description = "Adds a switch that plays live channels and videos with the ExoPlayer backend Twitch still ships " +
        "instead of Amazon IVS. Off until turned on in Lurk settings. Clips, encrypted streams and React Native " +
        "players stay on IVS. Lurk patches that hook IVS, such as Normal latency, Mute stitched ads and Show video " +
        "stats, don't act on ExoPlayer.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        // PlayerConfiguration(crashReporterUtil, forceExoPlayer, contentFormat): its backend getter returns
        // IVS for encrypted streams, ExoPlayer when forced, and IVS for every content format otherwise.
        val configuration = PlayerConfigurationFingerprint.twitchMatch("player configuration").classDef
        val constructor = configuration.methods.singleOrNull {
            it.name == "<init>" && it.parameterTypes.size == 3 && it.parameterTypes[1].toString() == "Z"
        } ?: throw PatchException("Twitch: no player configuration constructor")
        val format = constructor.parameterTypes[2].toString()
        constructor.addInstructions(0, """
            invoke-static/range { p2 .. p3 }, $LAYOUT_CLASS->forceExoPlayer(ZLjava/lang/Enum;)Z
            move-result p2
        """)
        if (!format.startsWith("L")) throw PatchException("Twitch: content format isn't an enum: $format")

        twitchSettings.add(SwitchSetting("force_exoplayer", "Force ExoPlayer",
            "Play live channels and videos with ExoPlayer instead of Amazon IVS. Applies to the next stream or video " +
                "you open. Lurk's IVS player options don't act on ExoPlayer.",
            Section.PLAYBACK, default = false))
    }
}
