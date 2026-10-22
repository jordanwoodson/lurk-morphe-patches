package io.github.jordanwoodson.patches.twitch.shared

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import io.github.jordanwoodson.patches.twitch.shared.Constants.TWITCH_VERSION

context(context: BytecodePatchContext)
internal fun Fingerprint.twitchMatch(what: String): Match = matchAllOrNull()?.singleOrNull()
    ?: throw PatchException("Twitch $TWITCH_VERSION: expected exactly one $what")
