package io.github.jordanwoodson.patches.twitch.shared

import app.morphe.patcher.patch.PatchException

/** A deliberately small relocator for our one compiler-produced predicate, not a general HBC linker. */
internal fun HermesBundle.installDelayedPredicate(input: ByteArray) {
    fun require(value: Boolean, why: String) { if (!value) throw PatchException("Twitch predicate: $why") }
    val donor = HermesBundle(input, compiledPredicate = true)
    val predicate = donor.function("lurkDelayedPredicate")
    val header = donor.predicateHeader(predicate)
    require(header.read32(4) == 2 && predicate.frameSize in 8..32 && predicate.numberRegisters == 0 &&
        predicate.nonPointerRegisters == 0 && predicate.flags == 0x1a, "compiler register/parameter/exception contract changed")
    // `event.type === "chat" || event.type === ...`: the opening of the original delayed callback.
    val type = id8("type")
    val original = matchingFunction(hex("89 03 01 44 04 03 00 $type 90 02 ${id16("chat")} 17 00 04 02 b0 12 00 44 04 03 00 $type"))
    require(original.body.size == 122 && original.flags and 0x18 == 0, "original callback changed")
    require(delayedAppend == null, "predicate already installed")
    data class Instruction(val old: Int, val bytes: ByteArray, val regs: IntArray, val branch: Int = 0)
    // The exact opcodes emitted by pinned HBC98 for delayed.js. No closures, literal buffers,
    // switches, globals writes, hidden table references, or arbitrary environment access are accepted.
    val shape = mapOf(
        0x10 to (3 to intArrayOf(1,2)), 0x17 to (4 to intArrayOf(1,2,3)),
        0x34 to (3 to intArrayOf(1)), 0x3b to (4 to intArrayOf(1,2)),
        0x3d to (2 to intArrayOf(1)), 0x44 to (5 to intArrayOf(1,2)),
        0x48 to (6 to intArrayOf(1,2)), 0x5d to (4 to intArrayOf(1,2,3)),
        0x6c to (4 to intArrayOf(1,2,3)), 0x6f to (6 to intArrayOf(1,2,3,4,5)),
        0x76 to (2 to intArrayOf(1)), 0x77 to (2 to intArrayOf(1)),
        0x89 to (3 to intArrayOf(1)), 0x8c to (6 to intArrayOf(1)),
        0x90 to (4 to intArrayOf(1)), 0x93 to (2 to intArrayOf(1)),
        0x96 to (2 to intArrayOf(1)), 0xae to (2 to intArrayOf()),
        0xb6 to (8 to intArrayOf(5)), 0xd3 to (4 to intArrayOf(2,3)),
        0xd5 to (4 to intArrayOf(2,3)))
    val instructions = mutableListOf<Instruction>()
    var at = 0
    var envLoads = 0
    while (at < predicate.body.size) {
        val opcode = predicate.body[at].toInt() and 255
        val (length, regs) = shape[opcode] ?: throw PatchException("Twitch predicate: unsupported opcode $opcode")
        require(at + length <= predicate.body.size, "truncated opcode")
        val bytes = predicate.body.copyOfRange(at,at+length)
        regs.forEach { require((bytes[it].toInt() and 255) < predicate.frameSize, "register outside frame") }
        if (opcode == 0x34) require(at == 0 && bytes[1].toInt() == 0 && bytes[2].toInt() == 0, "parent environment is not depth zero")
        if (opcode == 0x3b) { require(bytes[2].toInt() == 0 && bytes[3].toInt() == 0, "event is not parent slot zero"); envLoads++ }
        if (opcode == 0x89) require(bytes[2].toInt() == 1, "unexpected parameter")
        if (opcode == 0x8c) require(bytes.read32(2) in setOf(1998063107,1998063109,1998063110), "private global contract changed")
        instructions += Instruction(at,bytes,regs,if (opcode == 0xb6) 4 else if (opcode in setOf(0xae,0xd3,0xd5)) 1 else 0)
        at += length
    }
    require(envLoads == 2 && instructions.first().bytes[0].toInt() == 0x34, "event capture changed")
    val permitted = setOf("globalThis","type","chat","firstchatter","channelpointsreward","kind","delete","user","toLowerCase","userLogin","id","targetMsgId")
    val rewritten = instructions.map { instruction ->
        val bytes = instruction.bytes
        val opcode = bytes[0].toInt() and 255
        val id = when (opcode) { 0x44 -> bytes[4].toInt() and 255; 0x48 -> bytes.read16(4); 0x90 -> bytes.read16(2); else -> null }
        if (id == null) bytes.copyOf() else {
            val name = donor.strings[id]
            require(name in permitted,"unexpected string dependency $name")
            val target = stringId(name)
            // Always widen string operands. This keeps layout deterministic when target IDs exceed
            // the donor's short operand width; every branch and exception boundary is relocated below.
            val next = if (opcode == 0x90) ByteArray(6) else ByteArray(8)
            bytes.copyInto(next,0,0,if (opcode == 0x90) 2 else 4)
            next[0] = when (opcode) { 0x90 -> 0x91; 0x44 -> 0x46; else -> 0x49 }.toByte()
            next.put32(if (opcode == 0x90) 2 else 4,target)
            next
        }
    }
    val positions = mutableMapOf<Int,Int>()
    var size = 0
    instructions.forEachIndexed { i, instruction -> positions[instruction.old] = size; size += rewritten[i].size }
    positions[predicate.body.size] = size
    fun position(old: Int): Int = positions[old] ?: throw PatchException("Twitch predicate: target is not an opcode boundary")
    val result = ByteArray(size)
    instructions.forEachIndexed { i, instruction ->
        val next = rewritten[i]
        if (instruction.branch != 0) {
            val oldDistance = if (instruction.branch == 4) instruction.bytes.read32(1) else instruction.bytes[1].toInt()
            val distance = position(instruction.old + oldDistance) - position(instruction.old)
            require(instruction.old + oldDistance < predicate.body.size,"branch targets end")
            if (instruction.branch == 4) next.put32(1,distance)
            else { require(distance in -128..127,"short branch overflows"); next[1] = distance.toByte() }
        }
        next.copyInto(result,position(instruction.old))
    }
    val exceptions = donor.predicateExceptions(predicate).map { entry ->
        require(entry[0] < entry[1] && entry[1] <= predicate.body.size && entry[2] < predicate.body.size,"exception range outside body")
        require(predicate.body[entry[2]].toInt() == 0x77,"handler does not begin with Catch")
        IntArray(3) { position(entry[it]) }
    }
    require(exceptions.size == 1, "unexpected nested exception regions")
    appendDelayed(original,result,header,exceptions)
}

private fun ByteArray.read16(at: Int) = (this[at].toInt() and 255) or ((this[at+1].toInt() and 255) shl 8)
private fun ByteArray.read32(at: Int) = read16(at) or (read16(at+2) shl 16)
