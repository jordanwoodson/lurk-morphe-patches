package io.github.jordanwoodson.patches.rumble.premium

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

// The Gson DTO and its field keep their names; the getter is obfuscated.
// Match the field read itself so additional boolean getters cannot select the wrong flag.
internal object ProfilePremiumFingerprint : Fingerprint(
    definingClass = "Lcom/rumble/network/dto/profile/UserProfile;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(
            definingClass = "this",
            name = "isPremium",
            type = "Z",
            opcode = Opcode.IGET_BOOLEAN,
            location = MatchFirst(),
        ),
        opcode(Opcode.RETURN, MatchAfterImmediately()),
    ),
    custom = { method, _ -> method.implementation?.instructions?.count() == 2 },
)
