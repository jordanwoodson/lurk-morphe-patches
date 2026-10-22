package io.github.jordanwoodson.patches.kick.player

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.shared.OTA_UPDATES_OFF
import io.github.jordanwoodson.patches.kick.shared.hex
import io.github.jordanwoodson.patches.kick.shared.kickHermes
import io.github.jordanwoodson.patches.kick.shared.kickHermesPatch
import io.github.jordanwoodson.patches.shared.settings.ChoiceSetting
import io.github.jordanwoodson.patches.shared.settings.Section

private const val KEY = "vod_skip_seconds"

@Suppress("unused")
val seekIntervalPatch = bytecodePatch(
    name = "Seek interval",
    description = "Sets how far the rewind and forward buttons skip in videos and rewound live streams: 5, 15, 20 " +
        "or 30 seconds instead of 10. Starts at Kick's 10 seconds. Choose another in Lurk settings > Playback." + OTA_UPDATES_OFF,
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickHermesPatch)

    execute {
        fun scale(seconds: Int) = hex("73") + ByteArray(12) { if (it % 4 == 0) seconds.toByte() else 0 }
        // PlayerConfig.controls.skip.scale is {livestream, vod, 'live-vod', clip}, each slot filled from an array
        // literal right after the object is made. The `vod` and `live-vod` arrays read the same [10, 10, 10]
        // values, which no other literal shares; the live edge and clip arrays share bytes elsewhere and stay.
        val skipScale = kickHermes.literals.filter { it.keys == listOf("livestream", "vod", "live-vod", "clip") }
            .singleOrNull() ?: throw PatchException("Kick: expected one PlayerConfig skip scale object")
        val slots = kickHermes.slotArrays(skipScale)
        val vod = slots[1]
        val liveVod = slots[2]
        if (vod == null || liveVod == null || vod.start != liveVod.start || vod.end - vod.start != 13 ||
            !kickHermes.literal(vod.start, 13).contentEquals(scale(10))) {
            throw PatchException("Kick: the VOD skip scale is no longer [10, 10, 10] shared by vod and live-vod")
        }
        val seconds = listOf(5, 15, 20, 30)
        kickHermes.dataChoice(vod.start, KEY, scale(10), seconds.associate { "$it" to scale(it) },
            readers = listOf(vod, liveVod))
        kickSettings.add(
            ChoiceSetting(
                KEY,
                "Seek interval",
                "How far rewind and forward skip in videos.",
                Section.PLAYBACK,
                "0",
                listOf("Kick's default (10 s)" to "0") + seconds.map { "$it seconds" to "$it" },
                restart = true,
            ),
        )
    }
}
