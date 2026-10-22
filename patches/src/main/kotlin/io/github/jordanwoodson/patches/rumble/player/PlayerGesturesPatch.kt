package io.github.jordanwoodson.patches.rumble.player

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE_GESTURES
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val KEY = "player_gestures"
private const val MAIN_ACTIVITY = "Lcom/rumble/battles/landing/RumbleMainActivity;"
private const val MOTION_EVENT = "Landroid/view/MotionEvent;"

@Suppress("unused")
val playerGesturesPatch = bytecodePatch(
    name = "Player gestures",
    description = "In landscape, swipe up or down on the left of the video to change the brightness, on the right " +
        "to change the volume. Rumble's own double-tap to skip stays. Starts off. Turn it on in Lurk settings > " +
        "Playback.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        // The phone player is Media3's PlayerView under Compose controls, so the touch is taken where it enters
        // the app: the main activity (named in the manifest) gets a dispatchTouchEvent that asks the extension
        // first and otherwise dispatches as Activity does.
        val activity = mutableClassDefBy(MAIN_ACTIVITY)
        if (activity.methods.any { it.name == "dispatchTouchEvent" }) {
            throw RUMBLE.error("RumbleMainActivity has its own dispatchTouchEvent now; hook it instead")
        }
        val superclass = activity.superclass ?: throw RUMBLE.error("RumbleMainActivity has no superclass")
        activity.methods.add(
            ImmutableMethod(
                activity.type,
                "dispatchTouchEvent",
                listOf(ImmutableMethodParameter(MOTION_EVENT, null, null)),
                "Z",
                AccessFlags.PUBLIC.value,
                null,
                null,
                MutableMethodImplementation(3),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        invoke-static { p0, p1 }, $RUMBLE_GESTURES->dispatch(Landroid/app/Activity;$MOTION_EVENT)Z
                        move-result v0
                        if-eqz v0, :dispatch
                        return v0
                        :dispatch
                        invoke-super { p0, p1 }, $superclass->dispatchTouchEvent($MOTION_EVENT)Z
                        move-result v0
                        return v0
                    """,
                )
            },
        )

        rumbleSettings.add(
            SwitchSetting(
                KEY,
                "Player gestures",
                "In landscape: swipe on the left of the video for brightness, on the right for volume.",
                Section.PLAYBACK,
                default = false,
            ),
        )
    }
}
