package io.github.jordanwoodson.patches.twitch.shared

import app.morphe.patcher.patch.PatchException

/** HBC98 Metro registration scan, with exact call register flow and direct writable export signatures. */
internal fun HermesBundle.metroModule(exports: List<String>, direct: Boolean = true): Int {
    val ids = exports.map(::stringId)
    val define = stringId("__d")
    val global = metroInstructions(function("global"))
    val candidates = mutableListOf<Int>()
    for (i in 0 until global.size - 4) {
        val load = global[i]
        if (load.property() != define) continue
        val id = global[i+1]; val deps = global[i+2]; val closure = global[i+3]; val call = global[i+4]
        if (id.op !in setOf(0x8b,0x8c) || deps.op !in setOf(0x06,0x07,0x08) || closure.op != 0x84 || call.op != 0x70) continue
        if (call.u8(2) != load.u8(1) || call.u8(4) != closure.u8(1) || call.u8(5) != id.u8(1) || call.u8(6) != deps.u8(1)) continue
        val factory = functions.getOrNull(closure.u16(3)) ?: continue
        val body = metroInstructions(factory)
        if (ids.all { expected -> body.any { instruction ->
            (instruction.op in setOf(0x4a,0x4b) && instruction.u16(4) == expected) ||
                (!direct && ((instruction.op == 0x90 && instruction.u16(2) == expected) ||
                    (instruction.op == 0x91 && instruction.u32(2) == expected)))
        } }) candidates += if (id.op == 0x8b) id.u8(2) else id.u32(2)
    }
    return candidates.singleOrNull() ?: throw PatchException("Twitch Hermes: expected one direct Metro owner of ${exports.joinToString()}: $candidates")
}

private const val LENGTHS = "16ab238a4434433839333344444444444444444444444433445534345454522336755689686688668847476847455445446545565644455675747322423321136879574543636a46462222223233336443223eii573433253636368474747474747474747474747474747474747"
private class Instruction(val op:Int,val bytes:ByteArray) {
    fun u8(i:Int)=bytes[i].toInt() and 255
    fun u16(i:Int)=u8(i) or (u8(i+1) shl 8)
    fun u32(i:Int)=u16(i) or (u16(i+2) shl 16)
    fun property():Int?=when(op) {0x44->u8(4);0x45,0x48->u16(4);0x46,0x49->u32(4);else->null}
}
private fun metroInstructions(function:HermesFunction):List<Instruction> {
    val out=mutableListOf<Instruction>();val body=function.body
    var at=0;var end=body.size
    while(at<end) {
        val op=body[at].toInt() and 255
        if(op>=LENGTHS.length) throw PatchException("Twitch Hermes: unknown Metro opcode")
        val size=LENGTHS[op].digitToInt(36)
        if(at+size>end) throw PatchException("Twitch Hermes: truncated Metro instruction")
        val instruction=Instruction(op,body.copyOfRange(at,at+size))
        if(op==0xa6) end=minOf(end,at+instruction.u32(2))
        if(op==0xa7) end=minOf(end,at+instruction.u32(6))
        out+=instruction;at+=size
    }
    return out
}
