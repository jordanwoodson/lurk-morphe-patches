package io.github.jordanwoodson.patches.rumble.tipping

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE_INTERFACE
import io.github.jordanwoodson.patches.rumble.shared.enumConstant
import io.github.jordanwoodson.patches.rumble.shared.enumWith
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.settings.requireLocals
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions

/**
 * Passes the list of action buttons a channel or video header may draw (Follow, Join, Tip, Shop) through
 * RumbleInterface.actionButtons, which drops the ones whose switch is on. Shared by Hide tipping and Rants and
 * Hide Join and Shop buttons, so the list is filtered once.
 */
internal val actionButtonsPatch = bytecodePatch(
    description = "Lets the Rumble patches hide channel action buttons.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        val types = enumWith("Follow", "Join", "Tip", "Shop")
        Locators.record("rumble.hide_tipping.button_type", types.type)
        val (tip, join, shop) = listOf("Tip", "Join", "Shop").map { types.enumConstant(it) }
        CreatorActionButtonsFingerprint.single("creator action buttons").method.apply {
            val index = parameterTypes.indexOfFirst { it.toString() == "Ljava/util/List;" }
            if (index < 0) throw PatchException("Rumble: unexpected creator action buttons signature")
            // Both callers pass the list (their $default mask leaves it out), so the code that fills in
            // defaults doesn't overwrite it after this runs.
            val buttons = parameterRegister(index)
            requireLocals(4)
            addInstructions(
                0,
                """
                    move-object/from16 v0, v$buttons
                    sget-object v1, $tip
                    sget-object v2, $join
                    sget-object v3, $shop
                    invoke-static { v0, v1, v2, v3 }, $RUMBLE_INTERFACE->actionButtons(Ljava/util/List;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;
                    move-result-object v0
                    move-object/16 v$buttons, v0
                """,
            )
        }
    }
}
