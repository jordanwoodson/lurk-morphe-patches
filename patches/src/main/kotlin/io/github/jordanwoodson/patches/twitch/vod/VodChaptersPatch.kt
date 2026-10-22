package io.github.jordanwoodson.patches.twitch.vod

import app.morphe.patcher.Fingerprint
import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.twitch.player.VodPlayerOverlayFingerprint
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.twitch.player.hookPlayerViewAttach
import io.github.jordanwoodson.patches.twitch.player.playerPresenterClass
import io.github.jordanwoodson.patches.twitch.player.requireUnwritten
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.requireNotBranchTarget
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

private const val VOD_MODEL = "Ltv/twitch/android/models/videos/VodModel;"
private const val VIEW_DELEGATE = "Ltv/twitch/android/core/mvp/viewdelegate/BaseViewDelegate;"

/** Lurk's Chapters icon: Twitch's own list glyph copied under a new name, tinted white at runtime. */
private val chaptersIconPatch = resourcePatch {
    compatibleWith(COMPATIBILITY_TWITCH)
    execute {
        val source = get("res/drawable/list.xml")
        if (!source.exists()) throw PatchException("Twitch: no list icon")
        get("res/drawable/lurk_chapters.xml").writeBytes(source.readBytes())
    }
}

@Suppress("unused")
val vodChaptersPatch = bytecodePatch(
    name = "VOD chapters",
    description = "Adds a Chapters button to the native video (VOD) player that lists the stream's game and " +
        "category changes and jumps to one through Twitch's own seek. The list comes from Twitch's public " +
        "data: one request to gql.twitch.tv per video the native player loads, feed previews included, with " +
        "the app's client ID and without your account. On by default; the switch is in Lurk settings > Playback.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch, chaptersIconPatch)
    execute {
        val presenter = VodPresenterFingerprint.twitchMatch("VOD player presenter").originalClassDef

        // The VOD presenter implements Twitch's VOD seek interface; seekTo(int ms) is its only (int)->void.
        val vodInterface = presenter.interfaces.singleOrNull()
            ?: throw PatchException("Twitch: the VOD presenter's interfaces changed")
        val seek = classDefBy(vodInterface).methods.singleOrNull {
            it.parameterTypes.map { type -> type.toString() } == listOf("I") && it.returnType == "V"
        } ?: throw PatchException("Twitch: the VOD seek method changed")

        // Record the presenter and VOD each time a video is loaded (cll.q0(VodModel, int, String)).
        val load = mutableClassDefBy(presenter.type).methods.singleOrNull { method ->
            method.returnType == "V" &&
                method.parameterTypes.map { it.toString() } == listOf(VOD_MODEL, "I", "Ljava/lang/String;")
        } ?: throw PatchException("Twitch: the VOD load method changed")
        load.requireNotBranchTarget(0, "the VOD load method")
        load.addInstructions(0, invokeStatic("$VOD->vodLoaded(Ljava/lang/Object;Ljava/lang/Object;)V",
            load.parameterRegister(0), load.parameterRegister(1)))

        // Every native player presenter (live, VOD, clip) attaching its player view, so a tapped overlay
        // resolves to the presenter under it rather than to a preloaded neighbour. The VOD presenter is
        // one of these (it extends the base presenter that declares this method).
        val base = playerPresenterClass()
        if (!generateSequence(presenter.superclass) { type -> classDefByOrNull(type)?.superclass }
                .any { it == base.type }) {
            throw PatchException("Twitch: the VOD presenter no longer extends the player presenter")
        }
        hookPlayerViewAttach()

        // Seek exactly as Twitch does, through the presenter's own interface method.
        vodBridge("seekVod", """
            check-cast p0, $vodInterface
            invoke-interface { p0, p1 }, $vodInterface->${seek.name}(I)V
        """)

        // Add a Chapters button to the VOD/clip overlay, next to its settings gear.
        val match = VodPlayerOverlayFingerprint.twitchMatch("VOD player overlay")
        val method = match.method
        val code = method.implementation!!.instructions
        val id = match.instructionMatches[1]
        val idRegister = (id.instruction as OneRegisterInstruction).registerA
        val lookup = code[id.index + 1] as? FiveRegisterInstruction
        val cast = (id.index + 2..id.index + 5).firstOrNull { index ->
            code[index].opcode == Opcode.CHECK_CAST &&
                ((code[index] as ReferenceInstruction).reference as TypeReference).type == "Landroid/widget/ImageView;"
        }
        val arguments = lookup?.let {
            listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG).take(it.registerCount)
        }.orEmpty()
        if (idRegister !in arguments || code[id.index + 2].opcode != Opcode.MOVE_RESULT_OBJECT || cast == null) {
            throw PatchException("Twitch: the VOD player's settings button lookup changed")
        }
        val gear = (code[cast] as OneRegisterInstruction).registerA
        method.requireNotBranchTarget(cast + 1, "the VOD player's settings button")
        method.addInstructions(cast + 1,
            "invoke-static/range { v$gear .. v$gear }, $VOD->bindChapters(Landroid/view/View;)V")

        twitchSettings.add(SwitchSetting("vod_chapters", "VOD chapters",
            "Adds a Chapters button to the video (VOD) player that lists the stream's game and category " +
                "changes and jumps to one. One request to Twitch per video loaded, previews included, without " +
                "your account.",
            Section.PLAYBACK, default = true))
    }
}
