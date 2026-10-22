package io.github.jordanwoodson.patches.twitch.player

import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.requireLowRegisters
import io.github.jordanwoodson.patches.twitch.shared.requireNotBranchTarget
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

/** Smali descriptor of the extension's player class. */
internal const val PLAYER = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchPlayer;"

/** The base class of Twitch's native player presenters (live, VOD, clips, vertical). */
context(context: BytecodePatchContext)
internal fun playerPresenterClass(): ClassDef =
    PlayerPresenterFingerprint.twitchMatch("player presenter base class").originalClassDef

/**
 * The player presenter method the error screen's Retry calls, with (0, true): a new access token and playlist
 * for live streams, the same position for videos. An interface method of the presenter base class.
 */
context(context: BytecodePatchContext)
internal fun playerRetry(): MethodReference {
    val presenter = playerPresenterClass()
    val errorScreen = PlayerErrorRetryFingerprint.twitchMatch("player error screen").originalMethod
    val action = context.classDefBy(errorScreen.parameterTypes.last().toString())
    val retry = action.methods.flatMap { method -> method.implementation?.instructions?.toList().orEmpty() }
        .filter { it.opcode == Opcode.INVOKE_INTERFACE }
        .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
        .filter { it.returnType == "V" && it.parameterTypes.map { type -> type.toString() } == listOf("Ljava/lang/Integer;", "Z") }
        .distinct().singleOrNull()
        ?: throw PatchException("Twitch: the player error screen's Retry changed")
    if (retry.definingClass !in presenter.interfaces) throw PatchException("Twitch: Retry is not a player presenter call")
    return retry
}

private const val VIEW_DELEGATE = "Ltv/twitch/android/core/mvp/viewdelegate/BaseViewDelegate;"
private const val PLAYER_VIEWS = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchPlayerViews;"
private const val ATTACHED = "$PLAYER_VIEWS->attached(Ljava/lang/Object;Landroid/view/View;)V"

/**
 * Every native player presenter (live, VOD, clip) attaching its player view, so a long-press or a tapped
 * overlay resolves to the presenter under it rather than to a preloaded neighbour. Refresh stream and VOD
 * chapters both need it; the first to run inserts the one call, which TwitchPlayerViews passes on to
 * whichever of them is included.
 */
context(context: BytecodePatchContext)
internal fun hookPlayerViewAttach() {
    val match = Fingerprint(
        definingClass = playerPresenterClass().type, returnType = "V", parameters = listOf("L"),
        filters = listOf(
            fieldAccess(definingClass = VIEW_DELEGATE, type = "Landroid/view/View;", opcode = Opcode.IGET_OBJECT),
            methodCall(definingClass = "Landroid/view/View;", name = "addOnLayoutChangeListener"),
        ),
    ).twitchMatch("player view attach")
    val method = match.method
    if (method.implementation!!.instructions.any { (it as? ReferenceInstruction)?.reference?.toString() == ATTACHED }) return
    val read = match.instructionMatches.first()
    val view = (read.instruction as TwoRegisterInstruction).registerA
    val self = method.parameterRegister(0)
    method.requireUnwritten(self, read.index, "player presenter")
    method.requireNotBranchTarget(read.index + 1, "player view attach")
    // Mid-method, with no register proven free: fail rather than emit a call that would be dropped.
    method.requireLowRegisters("player view attach", self, view)
    method.addInstructions(read.index + 1, invokeStatic(ATTACHED, self, view))
}

/** Fails if an instruction before [end] writes [register], so it still holds the parameter there. */
internal fun MutableMethod.requireUnwritten(register: Int, end: Int, what: String) {
    implementation!!.instructions.take(end).forEach { instruction ->
        val written = (instruction as? OneRegisterInstruction)?.registerA
        val wide = instruction.opcode.setsWideRegister()
        if (instruction.opcode.setsRegister() && written != null &&
            (written == register || (wide && written + 1 == register))) {
            throw PatchException("Twitch: $what is overwritten")
        }
    }
}

/**
 * Gives an empty extension stub of [PLAYER] a body that calls into obfuscated app code, so the
 * extension never names R8 types.
 */
context(context: BytecodePatchContext)
internal fun bridge(name: String, smali: String) {
    val stub = context.mutableClassDefBy(PLAYER).methods.singleOrNull { it.name == name }
        ?: throw PatchException("Lurk: no $name stub")
    val code = stub.implementation!!.instructions
    if (code.size != 1 || code[0].opcode != Opcode.RETURN_VOID) throw PatchException("Lurk: $name stub is not empty")
    stub.addInstructions(0, smali)
}
