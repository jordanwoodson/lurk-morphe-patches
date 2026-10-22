package io.github.jordanwoodson.patches.kick.playback

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import io.github.jordanwoodson.patches.kick.player.KICK_PLAYER
import io.github.jordanwoodson.patches.kick.player.SetAutoMaxQualityFingerprint
import io.github.jordanwoodson.patches.kick.settings.kickMatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.settings.kickSettingsPatch
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.shared.OTA_UPDATES_OFF
import io.github.jordanwoodson.patches.kick.shared.kickHermes
import io.github.jordanwoodson.patches.kick.shared.kickHermesPatch
import io.github.jordanwoodson.patches.shared.bridge
import io.github.jordanwoodson.patches.shared.settings.ChoiceSetting
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val KEY = "default_video_quality"
private const val METERED = "quality_metered"

@Suppress("unused")
val defaultVideoQualityPatch = bytecodePatch(
    name = "Default video quality",
    description = "Sets the default adaptive quality ceiling to the selected height when that rendition exists. " +
        "Kick can still choose a lower quality. The player's Auto option uses this ceiling. A lower starting " +
        "ceiling can be set for mobile data in Lurk settings > Playback; it's off until then." + OTA_UPDATES_OFF,
    default = false,
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickHermesPatch, kickSettingsPatch)
    val quality by stringOption(
        key = "quality", default = "1080",
        values = listOf(1080, 720, 480, 360, 240).associate { "${it}p" to "$it" },
        title = "Default quality ceiling", description = "Adaptive playback ceiling. Repatch to change this height.",
        required = true,
    ) { it in listOf("1080", "720", "480", "360", "240") }
    execute {
        val height = quality!!.toInt()
        // The serialized PlayerConfig default is 720. Replacing only the initial store read loses
        // 1080 on the first load: _getResetStreamLoadingStates compares it with the old default and
        // restores getInitialStateFor's 720. Keep all ten consumers coherent without changing the
        // serialized literal table. Each anchor is the PlayerConfig read and the
        // defaultMaxAutoQualityHeight read of its result, with that consumer's registers and cache
        // slots and this bundle's string IDs; this identifies anonymous functions and repeated reads
        // without relying on function IDs. Explicit quality selection and the original reset/clamping
        // rules remain intact, using the selected ceiling wherever Kick previously used its 720 default
        // (including Auto).
        // (config result, its object, cache), (height result, cache): the height read's object is the config.
        val reads = listOf(
            listOf(9, 9, 0, 9, 1), // usePlayerStoreIVSModuleSlice
            listOf(9, 9, 1, 9, 18), // onData
            listOf(5, 5, 2, 5, 3), // _getResetStreamLoadingStates
            listOf(6, 6, 1, 5, 2), // setUserSelectedQuality fallback
            listOf(4, 4, 1, 4, 2), // setSelectedQualityGroupPreset: Auto
            listOf(4, 4, 0, 4, 1), // getInitialStateFor
            listOf(5, 7, 16, 6, 17), // cleanupState: no prior ceiling
            listOf(4, 5, 16, 4, 17), // cleanupState: clamp prior ceiling
            listOf(7, 7, 16, 7, 17), // cleanupState: reset preset
            listOf(5, 5, 6, 5, 7), // _applyQualityScale fallback
        )
        for ((config, source, configCache, result, cache) in reads) {
            val before = kickHermes.getById(config, source, configCache, "PlayerConfig") +
                kickHermes.getById(result, config, cache, "defaultMaxAutoQualityHeight")
            val constant = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                .put(0x8c.toByte()).put(result.toByte()).putInt(height).array()
            kickHermes.replaceMatchingFunction(before, KEY, before, before.copyOfRange(0, 6) + constant)
        }
        // Every ceiling reaches the player through the view's setAutoMaxQuality: the stream's starting one,
        // Auto, a picked quality and the re-apply on each player state. On a metered network the mobile-data
        // choice caps a starting ceiling: none, Kick's own 720, or this patch's height. Any other value is a
        // pick and passes through. Kick's stored choice isn't touched.
        SetAutoMaxQualityFingerprint.kickMatch("player view setAutoMaxQuality").method.addInstructions(0, """
            invoke-static/range { p0 .. p1 }, $KICK_PLAYER->autoQualityCeiling(Landroid/view/View;Ljava/lang/Integer;)Ljava/lang/Integer;
            move-result-object p1
        """)
        bridge(KICK_PLAYER, "lurkHeight", "const/16 v0, $height\nreturn v0", locals = 1)

        kickSettings.add(
            SwitchSetting(KEY, "Default video quality",
                "Uses an adaptive ${height}p default and Auto ceiling when available; player selections work normally.",
                Section.PLAYBACK, restart = true),
            ChoiceSetting(METERED, "Quality on mobile data",
                "The highest quality a stream starts at on metered networks. A quality you pick in the player " +
                    "still plays.", Section.PLAYBACK, "0",
                listOf("Same as on Wi-Fi" to "0", "720p" to "720", "480p" to "480", "360p" to "360", "160p" to "160")),
        )
    }
}
