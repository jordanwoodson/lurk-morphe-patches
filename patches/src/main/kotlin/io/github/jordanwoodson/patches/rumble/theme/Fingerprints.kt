package io.github.jordanwoodson.patches.rumble.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Rumble's dark grey for raised surfaces, as the ARGB value it passes to Compose's `Color(Long)`. The dark
 * theme's page, sheet and dialog colors are already black (`#000000`); this grey is the bottom bar, the
 * search fields, chips, tags and some cards. Dividers and button backgrounds use a lighter grey, `#283139`.
 */
internal const val SURFACE_GREY = 0xFF1B2127L

// The grey loaded and turned into a Compose color: const-wide, then Color(Long), a static (J)J, a move or
// two later.
private val greyColorFilters = listOf(
    literal(SURFACE_GREY, listOf(Opcode.CONST_WIDE)),
    methodCall(
        parameters = listOf("J"),
        returnType = "J",
        opcodes = listOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE),
        location = InstructionLocation.MatchAfterWithin(2),
    ),
)

// RumbleColors(isLight): the color set RumbleTheme provides through a CompositionLocal, one instance for light
// and one for dark, built in the holder's static initializer. Each color has a light and a dark field; the
// constructor builds them all with Color(Long), the surface grey among them. Nothing else in the app has a
// public (Z)V constructor that builds this grey.
internal object ThemeColorsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    parameters = listOf("Z"),
    filters = greyColorFilters,
)

// The palette: Rumble's named colors as static constants, with Material's light and dark color sets, all
// built in its static initializer. The same grey is used there for surfaces that stay dark in both themes
// (the bottom bar over Shorts, Premium banners) and, with transparency, for scrims.
internal object PaletteFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    parameters = emptyList(),
    filters = greyColorFilters,
)
