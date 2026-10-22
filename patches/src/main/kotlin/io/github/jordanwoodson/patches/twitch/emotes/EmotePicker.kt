package io.github.jordanwoodson.patches.twitch.emotes

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.shared.bridge
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import io.github.jordanwoodson.patches.twitch.shared.replaceInstruction
import io.github.jordanwoodson.patches.twitch.shared.requireKept
import io.github.jordanwoodson.patches.twitch.shared.requireNotBranchTarget
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

private const val EMOTES = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchEmotes;"
private const val OPTIONAL = "Ltv/twitch/android/util/Optional;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

/**
 * The emote picker and autocomplete (switch `emotes_picker`). Twitch builds the picker's sections itself:
 * the presenter groups the user's emote sets by owner and gives each owner's set a section with the owner's
 * name as its header. The hook adds one owner set per service to that list, made of the same Twitch emote
 * objects chat uses ([emoteType]), so Twitch draws them, inserts their code on a tap and loads their images
 * through the emote URL hook. Autocomplete asks its current source for matches; the call goes through the
 * extension, which appends the services' emotes when the source is the emote one.
 */
internal fun BytecodePatchContext.hookEmotePicker(emoteType: String) {
    val picker = EmotePickerFingerprint.twitchMatch("emote picker presenter").classDef
    val setClass = OwnerEmoteSetFingerprint.twitchMatch("owner emote set").classDef
    val ownerClass = EmoteOwnerFingerprint.twitchMatch("emote owner").classDef
    val ownerInit = ownerClass.methods.singleOrNull {
        it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(TUID, STRING, STRING, STRING)
    } ?: throw PatchException("Twitch: the emote owner constructor changed")
    val setInit = setClass.methods.singleOrNull {
        it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(ownerClass.type, STRING, "Ljava/util/List;")
    } ?: throw PatchException("Twitch: the owner emote set constructor changed")
    // The presenter's section builder (set, channel, surface): what the probe calls to check a section.
    val section = picker.methods.singleOrNull { method ->
        method.parameterTypes.map(CharSequence::toString).let { it.size == 3 && it[0] == setClass.superclass && it[1] == TUID }
    } ?: throw PatchException("Twitch: the emote picker's section builder changed")
    Locators.record("twitch.emote_picker.section", section)

    // The presenter combines the user's sets with the channel's (two Optionals) in one Function3 case; its
    // arguments are cast right at the start of that case. The sets list gets the services' sets appended.
    val sets = Fingerprint(
        name = "invoke", returnType = OBJECT, parameters = listOf(OBJECT, OBJECT, OBJECT),
        filters = listOf(
            checkCast(picker.type),
            checkCast(TUID, MatchAfterWithin(2)),
            checkCast("Ljava/util/List;", MatchAfterWithin(6)),
            checkCast(OPTIONAL, MatchAfterWithin(2)),
            checkCast(OPTIONAL, MatchAfterWithin(2)),
        ),
    ).twitchMatch("emote picker sets")
    sets.method.apply {
        val channelCast = sets.instructionMatches[1]
        val listCast = sets.instructionMatches[2]
        val channel = (channelCast.instruction as OneRegisterInstruction).registerA
        val list = (listCast.instruction as OneRegisterInstruction).registerA
        val insert = listCast.index + 1
        requireKept(channelCast.index, insert, channel, "emote picker channel")
        requireNotBranchTarget(insert, "emote picker sets")
        addInstructions(insert, """
            ${invokeStatic("$EMOTES->pickerSets(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;", list, channel)}
            move-result-object v$list
        """)
    }
    bridge(EMOTES, "emoteClass", "const-class v0, $emoteType\nreturn-object v0", locals = 1)
    bridge(EMOTES, "ownerSet", """
        new-instance v0, Ltv/twitch/android/models/Tuid;
        invoke-direct { v0, p0 }, Ltv/twitch/android/models/Tuid;-><init>(I)V
        new-instance v1, ${ownerClass.type}
        invoke-direct { v1, v0, p1, p2, p3 }, $ownerInit
        new-instance v0, ${setClass.type}
        invoke-direct { v0, v1, p4, p5 }, $setInit
        return-object v0
    """, locals = 2)

    // Autocomplete: the filter's call into its source goes through the extension, which makes the same call.
    val sourceMatch = EmoteAutocompleteFingerprint.twitchMatch("emote autocomplete source")
    val source = sourceMatch.classDef
    // The source's code-to-emote map, for the probe: the field read right before the LinkedHashMap cast.
    sourceMatch.method.implementation!!.instructions.toList().let { code ->
        val cast = sourceMatch.instructionMatches.first().index
        val map = (code.getOrNull(cast - 1) as? ReferenceInstruction)?.reference
        if (code.getOrNull(cast - 1)?.opcode != Opcode.IGET_OBJECT || map == null) {
            throw PatchException("Twitch: the emote autocomplete source's map moved")
        }
        Locators.record("twitch.emote_picker.autocomplete_map", map.toString())
        // Its sort order: the method's other cast, of the field it reads first.
        val order = code.take(cast).indexOfFirst { it.opcode == Opcode.CHECK_CAST }
        if (order < 1 || code[order - 1].opcode != Opcode.IGET_OBJECT) {
            throw PatchException("Twitch: the emote autocomplete source's order moved")
        }
        Locators.record("twitch.emote_picker.autocomplete_order", (code[order - 1] as ReferenceInstruction).reference.toString())
        Locators.record("twitch.emote_picker.autocomplete_order_type", (code[order] as ReferenceInstruction).reference.toString())
    }
    val filter = AutocompleteFilterFingerprint.twitchMatch("autocomplete filter")
    val call = filter.instructionMatches.first()
    val matches = (call.instruction as ReferenceInstruction).reference as MethodReference
    if (call.instruction.opcode != Opcode.INVOKE_INTERFACE || matches.definingClass !in source.interfaces) {
        throw PatchException("Twitch: the autocomplete filter no longer asks the emote source's interface")
    }
    val has = classDefBy(matches.definingClass).methods.singleOrNull {
        it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(STRING)
    } ?: throw PatchException("Twitch: the autocomplete source has no single code lookup")
    val arguments = call.instruction as FiveRegisterInstruction
    if (arguments.registerCount != 2) throw PatchException("Twitch: unexpected autocomplete call")
    filter.method.replaceInstruction(call.index,
        invokeStatic("$EMOTES->autocomplete(Ljava/lang/Object;Ljava/lang/CharSequence;)Ljava/util/ArrayList;",
            arguments.registerC, arguments.registerD))
    bridge(EMOTES, "sourceMatches",
        "check-cast p0, ${matches.definingClass}\ninvoke-interface { p0, p1 }, $matches\nmove-result-object p0\nreturn-object p0")
    bridge(EMOTES, "sourceHas",
        "check-cast p0, ${matches.definingClass}\ninvoke-interface { p0, p1 }, $has\nmove-result p0\nreturn p0")
    bridge(EMOTES, "isEmoteSource", "instance-of p0, p0, ${source.type}\nreturn p0")

    // An emote's code: the base class getter the emote class implements by returning the field its
    // constructor stores its second argument in (id, code, owner, asset type, modifications, type).
    val emote = classDefBy(emoteType)
    val emoteInit = emote.methods.singleOrNull {
        it.name == "<init>" && it.parameterTypes.size == 6 && it.parameterTypes[1].toString() == STRING
    }?.implementation ?: throw PatchException("Twitch: the emote constructor changed")
    val codeRegister = emoteInit.registerCount - 7 + 2
    val codeField = emoteInit.instructions.filter { it.opcode == Opcode.IPUT_OBJECT }
        .singleOrNull { (it as TwoRegisterInstruction).registerA == codeRegister }
        ?.let { (it as ReferenceInstruction).reference.toString() }
        ?: throw PatchException("Twitch: the emote constructor no longer stores its code")
    val getter = emote.methods.singleOrNull { method ->
        method.parameterTypes.isEmpty() && method.returnType == STRING && method.implementation?.instructions?.toList()?.let {
            it.size == 2 && it[0].opcode == Opcode.IGET_OBJECT && (it[0] as ReferenceInstruction).reference.toString() == codeField
        } == true
    } ?: throw PatchException("Twitch: the emote has no code getter")
    if (classDefBy(emote.superclass!!).methods.none { it.name == getter.name && it.returnType == STRING && it.parameterTypes.isEmpty() }) {
        throw PatchException("Twitch: the emote code getter isn't the base class's")
    }
    bridge(EMOTES, "emoteCode", """
        check-cast p0, ${emote.superclass}
        invoke-virtual { p0 }, ${emote.superclass}->${getter.name}()Ljava/lang/String;
        move-result-object p0
        return-object p0
    """)
}
