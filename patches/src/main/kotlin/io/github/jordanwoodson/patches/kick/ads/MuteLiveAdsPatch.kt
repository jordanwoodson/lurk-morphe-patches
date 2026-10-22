package io.github.jordanwoodson.patches.kick.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import io.github.jordanwoodson.patches.kick.player.PLAYER_VIEW
import io.github.jordanwoodson.patches.kick.settings.kickMatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.settings.kickSettingsPatch
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.shared.bridge
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.requireLowRegisters
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val KEY = "mute_live_ads"
private const val KICK_ADS = "Lio/github/jordanwoodson/extension/shared/kick/KickAds;"
private const val IVS_LISTENER = "Lcom/amazonaws/ivs/player/Player\$Listener;"
private const val IVS_CUE = "Lcom/amazonaws/ivs/player/Cue;"
private const val IVS_AD_BREAK = "Lcom/amazonaws/ivs/player/AdBreak;"

@Suppress("unused")
val muteLiveAdsPatch = bytecodePatch(
    name = "Mute stitched live ads",
    description = "Experimental: ads could not be observed signed out. While Kick's player reports an ad break " +
        "stitched into a live stream, mutes the player and covers the video with black and a small \"Ad break\", " +
        "then restores both when the break's time is up, it ends, or another stream loads. The ad still plays for " +
        "its full length and can't be skipped; this only silences and hides it. It acts on the ad signals Kick's " +
        "player can get: an ad cue or ad break from Amazon IVS, or an ad of a MediaTailor session, which Kick only " +
        "starts with Disable analytics off. None arrived in signed-out sessions, so this was checked only with " +
        "made-up ad signals fed to Kick's own player code. Starts off. Turn it on in Lurk settings > Playback.",
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickSettingsPatch)

    execute {
        hookPlayerListener()
        hookPlayerView()
        kickSettings.add(
            SwitchSetting(
                KEY,
                "Mute stitched live ads",
                "Experimental: ads could not be observed signed out. Mutes and blacks out an ad break Kick's player " +
                    "reports, until it ends. The ad still plays for its full length.",
                Section.PLAYBACK,
                default = false,
            ),
        )
    }
}

/**
 * Kick's player view listens to its IVS player through an anonymous Player.Listener, the type of its
 * `playerListener` field, that keeps the view in a synthetic field. Its onCue ignores every cue, and it keeps
 * Player.Listener's empty onAdBreakStarted and onAdBreakEnded. The cue hook goes in onCue; the two ad break methods
 * are added, each handing the view and the break to KickAds. IVS calls them on the main thread.
 */
private fun BytecodePatchContext.hookPlayerListener() {
    val listenerType = classDefBy(PLAYER_VIEW).fields.singleOrNull { it.name == "playerListener" }?.type
        ?: throw PatchException("Kick: the player view has no playerListener")
    val listener = mutableClassDefBy(listenerType)
    if (listener.superclass != IVS_LISTENER) throw PatchException("Kick: the player view's listener isn't an IVS Player.Listener")
    val outer = listener.fields.singleOrNull { it.type == PLAYER_VIEW && !AccessFlags.STATIC.isSet(it.accessFlags) }
        ?: throw PatchException("Kick: the IVS listener doesn't keep its player view")
    val view = "$listenerType->${outer.name}:$PLAYER_VIEW"
    Locators.record("kick.mute_live_ads.listener", listenerType)

    val onCue = listener.methods.singleOrNull {
        it.name == "onCue" && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(IVS_CUE)
    } ?: throw PatchException("Kick: the IVS listener has no onCue")
    onCue.apply {
        val self = parameterRegister(0)
        val cue = parameterRegister(1)
        requireLowRegisters("Mute stitched live ads", self, cue)
        // Kick's own first instruction overwrites `this` (the name for checkNotNullParameter), so `this` can hold
        // the view for the call without saving it.
        val first = implementation!!.instructions.first()
        if (first.opcode != Opcode.CONST_STRING || (first as OneRegisterInstruction).registerA != self) {
            throw PatchException("Kick: the IVS listener's onCue no longer starts by reusing its this register")
        }
        addInstructions(0, """
            iget-object v$self, v$self, $view
            ${invokeStatic("$KICK_ADS->onCue(Landroid/view/View;Ljava/lang/Object;)V", self, cue)}
        """)
    }

    val base = classDefBy(IVS_LISTENER)
    for (name in listOf("onAdBreakStarted", "onAdBreakEnded")) {
        fun signature(parameters: List<CharSequence>) = parameters.map(Any::toString) == listOf(IVS_AD_BREAK)
        if (base.methods.none { it.name == name && it.returnType == "V" && signature(it.parameterTypes) }) {
            throw PatchException("Kick: IVS Player.Listener has no $name(AdBreak)")
        }
        if (listener.methods.any { it.name == name }) {
            throw PatchException("Kick: the IVS listener now handles $name itself")
        }
        // p0 = v1 (this), p1 = v2 (the break).
        listener.methods.add(
            ImmutableMethod(
                listenerType, name, listOf(ImmutableMethodParameter(IVS_AD_BREAK, null, null)), "V",
                AccessFlags.PUBLIC.value, null, null, MutableMethodImplementation(3),
            ).toMutable().apply {
                addInstructions(0, """
                    iget-object v0, p0, $view
                    invoke-static { v0, p1 }, $KICK_ADS->$name(Landroid/view/View;Ljava/lang/Object;)V
                    return-void
                """)
            },
        )
    }
}

/**
 * The player view: its MediaTailor ad events, the mute React Native asks for (kept on during a break and restored
 * after it), and a new stream or the view's end, which end a break. KickAds mutes through the view's own setMuted.
 */
private fun BytecodePatchContext.hookPlayerView() {
    MediaTailorAdEventFingerprint.kickMatch("player view MediaTailor ad event").method.apply {
        if (!AccessFlags.STATIC.isSet(accessFlags)) throw PatchException("Kick: the MediaTailor ad event callback isn't static")
        Locators.record("kick.mute_live_ads.media_tailor_event", this)
        addInstructions(0, invokeStatic("$KICK_ADS->onMediaTailorEvent(Landroid/view/View;Ljava/lang/Object;)V",
            parameterRegister(0), parameterRegister(1)))
    }
    PlayerViewSetMutedFingerprint.kickMatch("player view setMuted").method.apply {
        addInstructions(0, """
            ${invokeStatic("$KICK_ADS->muteRequest(Landroid/view/View;Z)Z", parameterRegister(0), parameterRegister(1))}
            move-result p1
        """)
    }
    for ((fingerprint, what) in listOf(
        PlayerViewStreamUrlFingerprint to "player view setStreamUrl",
        PlayerViewLoadSourceFingerprint to "player view loadSource",
        PlayerViewDestroyFingerprint to "player view destroy",
    )) {
        fingerprint.kickMatch(what).method.apply {
            addInstructions(0, invokeStatic("$KICK_ADS->onLoad(Landroid/view/View;)V", parameterRegister(0)))
        }
    }
    bridge(KICK_ADS, "setMuted", """
        check-cast p0, $PLAYER_VIEW
        invoke-virtual { p0, p1 }, $PLAYER_VIEW->setMuted(Z)V
        return-void
    """)
    bridge(KICK_ADS, "adEventName", """
        check-cast p0, $MUX_AD_EVENT
        invoke-virtual { p0 }, $MUX_AD_EVENT->getEventName()Ljava/lang/String;
        move-result-object p0
        return-object p0
    """)
    bridge(KICK_ADS, "adEventSeconds", """
        check-cast p0, $MUX_AD_EVENT
        invoke-virtual { p0 }, $MUX_AD_EVENT->getDurationSeconds()Ljava/lang/Double;
        move-result-object p0
        return-object p0
    """)
}
