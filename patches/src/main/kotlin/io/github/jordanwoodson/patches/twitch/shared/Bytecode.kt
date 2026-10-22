package io.github.jordanwoodson.patches.twitch.shared

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException

context(context: BytecodePatchContext)
internal fun Fingerprint.twitchMatch(what: String): Match = matchAllOrNull()?.singleOrNull()
    ?: throw PatchException("Twitch 31.7.0: expected exactly one $what")
