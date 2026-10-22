package io.github.jordanwoodson.patches.twitch.ads

import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.requireLocals
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.twitch.shared.lowParameters
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

private const val KEY = "mute_stitched_ads"

@Suppress("unused")
val muteStitchedAdsPatch = bytecodePatch(
    name = "Mute stitched ads",
    description = "While an ad break that Twitch's server stitched into a stream plays, mutes the player and " +
        "covers the video with black, then restores both when the stream returns. Works in the native and " +
        "React Native players. The ad still plays and the break isn't shorter.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        // Native player: its HLS metadata handler sees each EXT-X-DATERANGE ad cue first. The
        // extension mutes the IVS player itself, which Twitch never does (it mutes with the volume).
        val cue = NativeAdCueFingerprint.twitchMatch("native player HLS metadata handler")
        val player = cue.classDef.fields.singleOrNull {
            it.type == IVS_MEDIA_PLAYER && !AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: throw PatchException("Twitch: native player has no unique IVS player")
        val playerField = "${cue.classDef.type}->${player.name}:$IVS_MEDIA_PLAYER"
        Locators.record("twitch.mute_stitched_ads.native_player", playerField)
        cue.method.apply {
            requireLocals(1)
            val p = lowParameters(0, 1, firstLocal = 1)
            addInstructions(0, """
                ${p.prologue}
                iget-object v0, v${p[0]}, $playerField
                ${invokeStatic("$ADS->onNativeCue(Ljava/lang/Object;Lorg/json/JSONObject;)V", 0, p[1])}
            """)
        }
        // The native player keeps one IVS player for every stream it loads: a new load ends a break.
        val load = cue.classDef.methods.filter { method ->
            method.implementation?.instructions?.any {
                val reference = (it as? ReferenceInstruction)?.reference as? MethodReference
                it.opcode == Opcode.INVOKE_INTERFACE && reference?.definingClass == IVS_MEDIA_PLAYER &&
                    reference.name == "load"
            } == true
        }.singleOrNull() ?: throw PatchException("Twitch: native player has no unique stream load")
        load.requireLocals(1)
        load.addInstructions(0, """
            move-object/from16 v0, p0
            iget-object v0, v0, $playerField
            ${invokeStatic("$ADS->onNativeLoad(Ljava/lang/Object;)V", 0)}
        """)

        // Native cover: the full-size root of Twitch's stitched-ad overlay, while it shows an ad.
        val overlay = StitchedAdOverlayFingerprint.twitchMatch("native stitched-ad overlay").method
        val adPlaying = StitchedAdPlayingFingerprint.twitchMatch("native stitched-ad playing state").originalClassDef.type
        if (overlay.implementation!!.instructions.none {
                it.opcode == Opcode.INSTANCE_OF && ((it as ReferenceInstruction).reference as TypeReference).type == adPlaying
            }) throw PatchException("Twitch: stitched-ad overlay no longer draws its ad-playing state")
        val root = classDefBy(BASE_VIEW_DELEGATE).fields.singleOrNull {
            it.type == "Landroid/view/View;" && !AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: throw PatchException("Twitch: view delegate has no unique root view")
        Locators.record("twitch.mute_stitched_ads.root_view", "$BASE_VIEW_DELEGATE->${root.name}:Landroid/view/View;")
        overlay.requireLocals(2)
        overlay.addInstructions(0, """
            move-object/from16 v0, p1
            instance-of v1, v0, $adPlaying
            move-object/from16 v0, p0
            iget-object v0, v0, $BASE_VIEW_DELEGATE->${root.name}:Landroid/view/View;
            ${invokeStatic("$ADS->coverNativeAd(Landroid/view/View;Z)V", 0, 1)}
        """)

        // React Native player: each player view owns one playback session, which holds the player
        // and gets every player's metadata, before it drops what it doesn't route to its ad state.
        // A new stream URL or a release (the player may return to a pool) ends the break. The
        // extension keys a break by the view, the session's display.
        hookRnPlaybackSession()

        twitchSettings.add(SwitchSetting(KEY, "Mute stitched ads",
            "Mute and black out ad breaks stitched into streams. The ad still plays for its full length.",
            Section.PLAYBACK))
    }
}

private const val DISPLAY_VIEW = "lurkDisplayView"
private const val ON_METADATA = "lurkOnMetadata"

/**
 * The session's private display and player getter are only reachable from its own class, and
 * handleMetadata has one free register, so two public static helpers go into the session:
 * its display as a View (null otherwise) and the metadata hook.
 */
private fun BytecodePatchContext.hookRnPlaybackSession() {
    val session = mutableClassDefBy(RN_PLAYBACK_SESSION)
    val display = session.fields.singleOrNull { it.type == RN_PLAYBACK_DISPLAY && !AccessFlags.STATIC.isSet(it.accessFlags) }
        ?: throw PatchException("Twitch: React Native playback session has no unique display")
    if (session.methods.none { it.name == "getPlayer" && it.parameterTypes.isEmpty() && it.returnType == IVS_MEDIA_PLAYER }) {
        throw PatchException("Twitch: React Native playback session has no player getter")
    }
    if (classDefBy(RN_PLAYER_VIEW).interfaces.none { it == RN_PLAYBACK_DISPLAY }) {
        throw PatchException("Twitch: React Native player view is no longer its session's display")
    }
    fun helper(name: String, parameters: List<String>, returnType: String, registers: Int, smali: String) =
        session.methods.add(ImmutableMethod(RN_PLAYBACK_SESSION, name,
            parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            MutableMethodImplementation(registers)).toMutable().apply { addInstructions(0, smali) })
    val view = "$RN_PLAYBACK_SESSION->$DISPLAY_VIEW($RN_PLAYBACK_SESSION)Landroid/view/View;"
    // p0 = v1.
    helper(DISPLAY_VIEW, listOf(RN_PLAYBACK_SESSION), "Landroid/view/View;", 2, """
        iget-object v0, p0, $RN_PLAYBACK_SESSION->${display.name}:$RN_PLAYBACK_DISPLAY
        instance-of v1, v0, Landroid/view/View;
        if-eqz v1, :none
        check-cast v0, Landroid/view/View;
        return-object v0
        :none
        const/4 v0, 0x0
        return-object v0
    """)
    // p0..p2 = v2..v4.
    helper(ON_METADATA, listOf(RN_PLAYBACK_SESSION, "Ljava/lang/String;", "Ljava/nio/ByteBuffer;"), "V", 5, """
        invoke-static { p0 }, $view
        move-result-object v0
        invoke-direct { p0 }, $RN_PLAYBACK_SESSION->getPlayer()$IVS_MEDIA_PLAYER
        move-result-object v1
        invoke-static { v0, v1, p1, p2 }, $ADS->onRnCue(Landroid/view/View;Ljava/lang/Object;Ljava/lang/String;Ljava/nio/ByteBuffer;)V
        return-void
    """)

    RnPlayerMetadataFingerprint.twitchMatch("React Native player metadata").method.apply {
        addInstructions(0, invokeStatic("$RN_PLAYBACK_SESSION->$ON_METADATA($RN_PLAYBACK_SESSION" +
            "Ljava/lang/String;Ljava/nio/ByteBuffer;)V", parameterRegister(0), parameterRegister(1), parameterRegister(2)))
    }
    RnPlayerStreamUrlFingerprint.twitchMatch("React Native player stream URL").method.apply {
        requireLocals(1)
        val p = lowParameters(1, firstLocal = 1)
        addInstructions(0, """
            ${p.prologue}
            ${invokeStatic(view, parameterRegister(0))}
            move-result-object v0
            ${invokeStatic("$ADS->onRnStreamUrl(Landroid/view/View;Ljava/lang/String;)V", 0, p[1])}
        """)
    }
    RnPlayerReleaseFingerprint.twitchMatch("React Native player release").method.apply {
        requireLocals(1)
        addInstructions(0, """
            ${invokeStatic(view, parameterRegister(0))}
            move-result-object v0
            ${invokeStatic("$ADS->onRnRelease(Landroid/view/View;)V", 0)}
        """)
    }
}
