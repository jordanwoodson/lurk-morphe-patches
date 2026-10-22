package io.github.jordanwoodson.patches.rumble.shared

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

// R8 renames Rumble's Kotlin classes and methods, but keeps the strings Kotlin generates:
// a data object's toString() returns its source name, and constructors check each parameter
// against its name. These helpers find code through those strings and through signatures.

internal fun Method.parameters() = parameterTypes.map { it.toString() }

internal fun Method.instructionsOf(opcode: Opcode) =
    implementation?.instructions?.filter { it.opcode == opcode }.orEmpty()

/** Types created with new-instance in [method], such as the coroutine a suspend function starts. */
internal fun Method.newInstanceTypes() = instructionsOf(Opcode.NEW_INSTANCE)
    .map { ((it as ReferenceInstruction).reference as TypeReference).type }

/** Static fields [method] reads, as smali references ("Lpkg/A;->a:Lpkg/A;"). */
internal fun Method.staticFieldReads() = instructionsOf(Opcode.SGET_OBJECT)
    .map { (it as ReferenceInstruction).reference as FieldReference }

/** Kotlin's Unit, as [method] reads it. R8 renames the field, so take it from the code. */
internal fun Method.unitReference(): String =
    staticFieldReads().firstOrNull { it.type == "Lkotlin/Unit;" }?.toString()
        ?: throw PatchException("Rumble: no Unit read in $definingClass->$name")

/**
 * The INSTANCE field of the Kotlin data object named [name] (its toString() returns exactly that),
 * as a smali field reference. With [among], only those class types are considered.
 */
internal fun BytecodePatchContext.kotlinObject(name: String, among: Collection<String>? = null): String {
    val candidates = classDefByStrings(name, StringComparisonType.EQUALS).filter { classDef ->
        (among == null || classDef.type in among) && classDef.toStringReturns(name)
    }
    val classDef = candidates.singleOrNull()
        ?: throw PatchException("Rumble: expected one Kotlin object named $name, found ${candidates.size}")
    val field = classDef.staticFields.singleOrNull { it.type == classDef.type }
        ?: throw PatchException("Rumble: $name (${classDef.type}) has no INSTANCE field")
    return "${classDef.type}->${field.name}:${field.type}"
}

private fun ClassDef.toStringReturns(name: String): Boolean {
    val toString = methods.firstOrNull {
        it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
    } ?: return false
    val strings = toString.instructionsOf(Opcode.CONST_STRING)
        .map { ((it as ReferenceInstruction).reference as StringReference).string }
    return strings == listOf(name)
}

/** The one match of this fingerprint, or a PatchException naming [what]. */
context(context: BytecodePatchContext)
internal fun Fingerprint.single(what: String): Match = matchAllOrNull()?.singleOrNull()
    ?: throw PatchException("Rumble: expected exactly one $what. " +
        "These patches target 3.21.0; recheck the code before updating compatibility." +
        ((this as? ComposableFingerprint)?.let {
            " It is found by its Compose trace label \"${it.label} (\". A build compiled without Compose " +
                "source information has no such labels, and then every Compose hook fails this way; " +
                "tests/rumble/verify_compose.py <apk> lists which labels are missing."
        } ?: ""))

/**
 * A Compose function, found by the trace label the Compose compiler gives it
 * ("com.rumble.battles.….Name (File.kt:line)"). The line number is left out, since it moves.
 */
internal class ComposableFingerprint(val label: String) : Fingerprint(
    returnType = "V",
    filters = listOf(string("$label (", StringComparisonType.STARTS_WITH)),
)

internal fun composableFingerprint(qualifiedName: String) = ComposableFingerprint(qualifiedName)

/**
 * The getter of a field in one of Rumble's Gson DTOs (com.rumble.network.dto). R8 keeps those classes'
 * names and field names, since Gson needs them, but renames the getters.
 */
internal fun dtoGetterFingerprint(dtoClass: String, field: String, type: String) = Fingerprint(
    definingClass = dtoClass,
    returnType = type,
    parameters = emptyList(),
    filters = listOf(fieldAccess(definingClass = "this", name = field, type = type)),
    // toString() reads every field too.
    custom = { method, _ -> method.name != "toString" },
)

/** The static field holding the enum constant named [name] (R8 renames the fields, not the names). */
internal fun ClassDef.enumConstant(name: String): String {
    val instructions = methods.single { it.name == "<clinit>" }.implementation!!.instructions.toList()
    val nameIndex = instructions.indexOfFirst {
        it.opcode == Opcode.CONST_STRING &&
            ((it as ReferenceInstruction).reference as StringReference).string == name
    }
    val field = instructions.drop(nameIndex + 1).firstOrNull {
        it.opcode == Opcode.SPUT_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).type == type
    }
    if (nameIndex < 0 || field == null) throw PatchException("Rumble: no enum constant $name in $type")
    return (field as ReferenceInstruction).reference.toString()
}

/** The enum class whose constants include all of [names]. */
internal fun BytecodePatchContext.enumWith(vararg names: String): ClassDef {
    val candidates = classDefByStrings(names.first(), StringComparisonType.EQUALS).filter { classDef ->
        classDef.superclass == "Ljava/lang/Enum;" && names.all { name ->
            classDef.methods.any { method ->
                method.name == "<clinit>" && method.instructionsOf(Opcode.CONST_STRING)
                    .any { ((it as ReferenceInstruction).reference as StringReference).string == name }
            }
        }
    }
    return candidates.singleOrNull()
        ?: throw PatchException("Rumble: expected one enum with ${names.joinToString()}, found ${candidates.size}")
}

internal fun MutableClass.singleMethod(what: String, predicate: (MutableMethod) -> Boolean): MutableMethod =
    methods.filter(predicate).singleOrNull()
        ?: throw PatchException("Rumble: expected one $what in $type. " +
            "These patches target 3.21.0; recheck the class before updating compatibility.")
