package io.github.jordanwoodson.patches.shared

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Morphe silently drops a non-range `invoke-*` whose register is above v15: the patch applies without
 * an error and the call is simply missing from the patched dex. These helpers make that impossible to
 * write by accident. Build injected calls with [invokeStatic] (or [invoke]) from numeric registers, or
 * guard a hand-written `invoke-static { p0, p1 }` with [requireLowRegisters].
 */

/** The register number of parameter [index] (0 is `this` for instance methods), as a v register. */
internal fun MutableMethod.parameterRegister(index: Int): Int {
    val sizes = (if (AccessFlags.STATIC.isSet(accessFlags)) emptyList() else listOf(1)) +
        parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    if (index !in sizes.indices) throw PatchException("$definingClass->$name has no parameter $index")
    return implementation!!.registerCount - sizes.sum() + sizes.take(index).sum()
}

/** Every register of the method's parameters, lowest first; `this` is the first one for instance methods. */
internal fun MutableMethod.parameterRegisters(): List<Int> {
    val sizes = (if (AccessFlags.STATIC.isSet(accessFlags)) emptyList() else listOf(1)) +
        parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    val first = implementation!!.registerCount - sizes.sum()
    return sizes.runningFold(first) { register, size -> register + size }.dropLast(1)
}

/**
 * Fails unless every one of [registers] fits a non-range invoke (v0..v15), so a hand-written
 * `invoke-static { p0, p1 }` can't vanish when a later app version adds locals to the method.
 * [what] names the hook in the error.
 */
internal fun MutableMethod.requireLowRegisters(what: String, vararg registers: Int) {
    val high = registers.filter { it > 15 }
    if (high.isNotEmpty()) {
        throw PatchException(
            "$what: $definingClass->$name keeps ${high.joinToString { "v$it" }} above v15; " +
                "a non-range invoke there would be dropped silently. Use invokeStatic() or /range.",
        )
    }
}

/** [requireLowRegisters] for every parameter register of the method. */
internal fun MutableMethod.requireLowParameterRegisters(what: String) =
    requireLowRegisters(what, *parameterRegisters().toIntArray())

/**
 * A static call as one smali line: the plain form when every register is v15 or below, the `/range`
 * form otherwise. The range form needs contiguous registers; when they aren't, the helper fails with a
 * message instead of emitting a call that would be dropped. Copy the values into fresh low locals
 * first in that case (`move-object/from16 v0, p1` after `requireLocals`).
 */
internal fun invokeStatic(method: String, vararg registers: Int) = invoke("invoke-static", method, *registers)

/** [invokeStatic] for any `invoke-*` [kind] (`invoke-virtual`, `invoke-interface`, ...). */
internal fun invoke(kind: String, method: String, vararg registers: Int): String {
    if (registers.isEmpty()) return "$kind {}, $method"
    if (registers.all { it <= 15 }) return "$kind { ${registers.joinToString { "v$it" }} }, $method"
    val contiguous = registers.withIndex().all { (i, register) -> register == registers[0] + i }
    if (!contiguous) {
        throw PatchException(
            "Lurk: registers ${registers.joinToString { "v$it" }} for $method are above v15 and not contiguous; " +
                "copy them into low registers before the call",
        )
    }
    return "$kind/range { v${registers.first()} .. v${registers.last()} }, $method"
}
