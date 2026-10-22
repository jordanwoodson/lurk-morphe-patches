package io.github.jordanwoodson.patches.twitch.shared

import app.morphe.patcher.patch.PatchException
import java.io.File

/**
 * Checks HermesBundle.requireSafeEdit, which every Hermes edit passes through, on real functions of the
 * pinned stock bundle: an edit whose interior a jump lands in is refused (the helpers pad with
 * Unreachable, which aborts the app), unless the replacement keeps the original bytes from the landing
 * instruction on; edits must start on an instruction; a replacement can't jump mid-instruction.
 * Usage: HermesEditSafetyProbeKt <stock index.android.bundle>
 */
fun main(args: Array<String>) {
    val stock = File(args[0]).readBytes()
    val bundle = HermesBundle(stock)

    // The length table must walk every function of the bundle to its end or its switch table; only string
    // switches, which the decoder refuses, may stop it.
    val failures = bundle.functions.filter { it.body.isNotEmpty() }.mapNotNull { runCatching { bundle.controlFlow(it) }.exceptionOrNull() }
    check(failures.all { "string switch" in it.message.orEmpty() }) { "decoder failures: ${failures.take(5)}" }
    println("ok   decoded ${bundle.functions.size - failures.size} of ${bundle.functions.size} functions " +
        "(${failures.size} with string switches)")

    // A forward jump to an instruction T whose predecessor P starts after the jump, with T at least
    // 3 bytes long: editing P and T together puts the jump's landing point inside the edit.
    data class Case(val function: HermesFunction, val p: Int, val t: Int, val tEnd: Int)
    val case = bundle.functions.asSequence().filter { it.body.size in 16..4096 && it.flags and 8 == 0 }.mapNotNull { function ->
        val flow = runCatching { bundle.controlFlow(function) }.getOrNull() ?: return@mapNotNull null
        val starts = flow.map { it.first }
        val incoming = flow.flatMap { (from, targets) -> targets.map { from to it } }.groupBy({ it.second }, { it.first })
        flow.indices.drop(1).dropLast(1).firstNotNullOfOrNull { i ->
            val t = starts[i]
            val tEnd = starts[i + 1]
            val p = starts[i - 1]
            val sources = incoming[t].orEmpty()
            Case(function, p, t, tEnd).takeIf { tEnd - t >= 3 && sources.isNotEmpty() && sources.all { it < p } }
        }
    }.first()
    val body = case.function.body
    val name = case.function.name.ifEmpty { "anonymous function" }
    val tLength = case.tEnd - case.t
    val pLength = case.t - case.p
    println("probe function: $name, P at ${case.p} ($pLength bytes), jump target T at ${case.t} ($tLength bytes)")

    fun refused(what: String, relative: Int, after: ByteArray, expected: String) {
        val failure = runCatching { bundle.requireSafeEdit(case.function, "probe", relative, after) }.exceptionOrNull()
        check(failure is PatchException && expected in failure.message.orEmpty()) { "$what: expected \"$expected\", got $failure" }
        println("ok   refused: $what")
    }
    fun accepted(what: String, relative: Int, after: ByteArray) {
        bundle.requireSafeEdit(case.function, "probe", relative, after)
        println("ok   accepted: $what")
    }

    refused("Unreachable padding over a jump target", case.p, ByteArray(case.tEnd - case.p), "jumps into the edited bytes")
    accepted("the jump target replaced from its own start", case.t, ByteArray(tLength))
    accepted("padding before a jump target that keeps its original bytes", case.p,
        ByteArray(pLength) + body.copyOfRange(case.t, case.tEnd))
    if (pLength > 1) {
        refused("an edit starting mid-instruction", case.p + 1, ByteArray(case.tEnd - case.p - 1), "does not start and end on instructions")
    }
    // Jmp +1 (0xae 0x01) lands inside its own two bytes.
    refused("a replacement jumping mid-instruction", case.t, byteArrayOf(0xae.toByte(), 1) + ByteArray(tLength - 2),
        "jumps into itself mid-instruction")
    // An undecodable replacement: padding, then a GetById (six bytes long) cut off by the edit's end.
    refused("a replacement that is not whole instructions", case.t, ByteArray(tLength - 1) + byteArrayOf(0x45),
        "truncated instruction")
    println("PASS: Hermes edits refuse jumps into replaced bytes")
}
