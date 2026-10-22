package io.github.jordanwoodson.patches.rumble.shared

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import io.github.jordanwoodson.patches.shared.compose.constructorParameterType
import io.github.jordanwoodson.patches.shared.compose.overwrittenNext
import io.github.jordanwoodson.patches.shared.compose.requireNoBranchTo
import io.github.jordanwoodson.patches.shared.compose.requireUnwritten

// Register checks from patches/shared/compose, with Rumble's name in their errors.

internal fun MutableMethod.requireUnwritten(register: Int, from: Int, to: Int, what: String) =
    requireUnwritten(RUMBLE, register, from, to, what)

internal fun MutableMethod.overwrittenNext(index: Int): Int = overwrittenNext(RUMBLE, index)

internal fun MutableMethod.requireNoBranchTo(index: Int, what: String) = requireNoBranchTo(RUMBLE, index, what)

internal fun MutableMethod.constructorParameterType(name: String): String = constructorParameterType(RUMBLE, name)
