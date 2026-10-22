package io.github.jordanwoodson.patches.shared.compose

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

// R8 renames an app's Kotlin classes and methods but keeps the strings Kotlin and the Compose compiler
// generate: a data object's toString() returns its source name, constructors check each parameter against its
// name, enum constants keep their names, composables carry a trace label, and Gson DTOs keep their class and
// field names. These helpers find code through those strings and through signatures, for any app.

internal fun Method.parameters() = parameterTypes.map { it.toString() }

internal fun Method.instructionsOf(opcode: Opcode) =
    implementation?.instructions?.filter { it.opcode == opcode }.orEmpty()

/** Types created with new-instance in this method, such as the coroutine a suspend function starts. */
internal fun Method.newInstanceTypes() = instructionsOf(Opcode.NEW_INSTANCE)
    .map { ((it as ReferenceInstruction).reference as TypeReference).type }

/** Static fields this method reads, as smali references ("Lpkg/A;->a:Lpkg/A;"). */
internal fun Method.staticFieldReads() = instructionsOf(Opcode.SGET_OBJECT)
    .map { (it as ReferenceInstruction).reference as FieldReference }

/** The string a const-string loads, or null for any other instruction. */
internal fun Instruction.constString() =
    if (opcode == Opcode.CONST_STRING) ((this as ReferenceInstruction).reference as StringReference).string else null

/** Every string this method loads with const-string. */
internal fun Method.constStrings() = implementation?.instructions?.mapNotNull { it.constString() }.orEmpty()

/** Whether [method] (a smali method reference) is called anywhere in this method. */
internal fun Method.calls(method: String) = implementation?.instructions?.any {
    it is ReferenceInstruction && it.reference.toString() == method
} == true

/** This method as a smali reference ("Lpkg/A;->name(Lpkg/B;I)V"). */
internal fun Method.reference() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** The methods this method calls, with their instruction indexes. */
internal fun Method.methodCalls(): List<Pair<Int, MethodReference>> =
    implementation?.instructions?.withIndex()?.mapNotNull { (index, instruction) ->
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let { index to it }
    }.orEmpty()

/**
 * The patchable twin of [method] of [classDef], a class found while iterating (classDefForEach), which hands out
 * immutable definitions.
 */
internal fun BytecodePatchContext.mutableTwin(classDef: ClassDef, method: Method): MutableMethod =
    mutableClassDefBy(classDef).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }

/** Kotlin's Unit, as this method reads it. R8 renames the field, so take it from the code. */
internal fun Method.unitReference(app: AppCode): String =
    staticFieldReads().firstOrNull { it.type == "Lkotlin/Unit;" }?.toString()
        ?: throw app.error("no Unit read in $definingClass->$name")

/**
 * The INSTANCE field of the Kotlin data object named [name] (its toString() returns exactly that), as a smali
 * field reference. With [among], only those class types are considered.
 */
internal fun BytecodePatchContext.kotlinObject(app: AppCode, name: String, among: Collection<String>? = null): String {
    val candidates = classDefByStrings(name, StringComparisonType.EQUALS).filter { classDef ->
        (among == null || classDef.type in among) && classDef.toStringReturns(name)
    }
    val classDef = candidates.singleOrNull()
        ?: throw app.error("expected one Kotlin object named $name, found ${candidates.size}")
    val field = classDef.staticFields.singleOrNull { it.type == classDef.type }
        ?: throw app.error("$name (${classDef.type}) has no INSTANCE field")
    return "${classDef.type}->${field.name}:${field.type}"
}

private fun ClassDef.toStringReturns(name: String): Boolean {
    val toString = methods.firstOrNull {
        it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
    } ?: return false
    return toString.constStrings() == listOf(name)
}

/** The one match of this fingerprint, or a PatchException naming [what]. */
context(context: BytecodePatchContext)
internal fun Fingerprint.single(app: AppCode, what: String): Match = matchAllOrNull()?.singleOrNull()
    ?: throw app.error("expected exactly one $what. ${app.recheck}" +
        ((this as? ComposableFingerprint)?.let {
            " It is found by its Compose trace label \"${it.label} (\". A build compiled without Compose " +
                "source information has no such labels, and then every Compose hook fails this way; " +
                "tests/<app>/verify_compose.py <apk> lists which labels are missing."
        } ?: ""))

/**
 * A Compose function, found by the trace label the Compose compiler gives it ("com.example.….Name
 * (File.kt:line)"). The line number is left out, since it moves.
 */
internal class ComposableFingerprint(val label: String) : Fingerprint(
    returnType = "V",
    filters = listOf(string("$label (", StringComparisonType.STARTS_WITH)),
)

/**
 * The getter of a field in a Gson DTO. R8 keeps those classes' names and field names, since Gson needs them,
 * but renames the getters.
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
internal fun ClassDef.enumConstant(app: AppCode, name: String): String {
    val instructions = methods.single { it.name == "<clinit>" }.implementation!!.instructions.toList()
    val nameIndex = instructions.indexOfFirst { it.constString() == name }
    val field = instructions.drop(nameIndex + 1).firstOrNull {
        it.opcode == Opcode.SPUT_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).type == type
    }
    if (nameIndex < 0 || field == null) throw app.error("no enum constant $name in $type")
    return (field as ReferenceInstruction).reference.toString()
}

/** The enum class whose constants include all of [names]. */
internal fun BytecodePatchContext.enumWith(app: AppCode, vararg names: String): ClassDef {
    val candidates = classDefByStrings(names.first(), StringComparisonType.EQUALS).filter { classDef ->
        classDef.superclass == "Ljava/lang/Enum;" && names.all { name ->
            classDef.methods.any { method -> method.name == "<clinit>" && name in method.constStrings() }
        }
    }
    return candidates.singleOrNull()
        ?: throw app.error("expected one enum with ${names.joinToString()}, found ${candidates.size}")
}

internal fun MutableClass.singleMethod(app: AppCode, what: String, predicate: (MutableMethod) -> Boolean): MutableMethod =
    methods.filter(predicate).singleOrNull()
        ?: throw app.error("expected one $what in $type. ${app.recheck}")
