package io.github.jordanwoodson.patches.rumble.shared

import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.jordanwoodson.patches.shared.compose.clickableCall
import io.github.jordanwoodson.patches.shared.compose.staticRangeCalls as composeStaticRangeCalls

/** The static methods this method calls with invoke-static/range, with their instruction indexes. */
internal fun Method.staticRangeCalls(): List<Pair<Int, MethodReference>> = composeStaticRangeCalls()

/** Compose's Modifier.clickable in its default-argument form, as this method calls it (see patches/shared/compose). */
internal fun Method.clickableCall(modifier: String): String = clickableCall(RUMBLE, modifier)
