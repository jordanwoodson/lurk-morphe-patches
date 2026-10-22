package io.github.jordanwoodson.patches.twitch.clips

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.twitch.settings.twitchSettings
import io.github.jordanwoodson.patches.twitch.settings.twitchSettingsPatch
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH
import io.github.jordanwoodson.patches.twitch.shared.addInstructions
import io.github.jordanwoodson.patches.twitch.shared.requireNotBranchTarget
import io.github.jordanwoodson.patches.twitch.shared.twitchMatch

private const val CLIPS = "Lio/github/jordanwoodson/extension/shared/twitch/TwitchClips;"
private const val VIEW_DELEGATE = "Ltv/twitch/android/core/mvp/viewdelegate/BaseViewDelegate;"

@Suppress("unused")
val clipDownloadPatch = bytecodePatch(
    name = "Clip download",
    description = "Adds \"Download clip\" to the menu (⋮) of a clip in Twitch's clips feed, where clip links open. " +
        "It asks gql.twitch.tv for the clip's video addresses and signed access token with the app's Client-ID " +
        "only (no account), and Android's download manager saves the best quality as an MP4 in Downloads/Twitch. " +
        "Starts off. Turn it on in Lurk settings > Playback.",
) {
    compatibleWith(COMPATIBILITY_TWITCH)
    dependsOn(twitchSettingsPatch)
    execute {
        // The clips feed's menu sheet: its fragment binds the sheet's view delegate to its presenter, which holds
        // the feed item the menu is for.
        val match = Fingerprint(
            definingClass = "Ltv/twitch/android/feature/discovery/feed/overflow/DiscoveryFeedOverflowMenuFragment;",
            name = "onCreateView", returnType = "Landroid/view/View;",
            filters = listOf(methodCall(definingClass = "Ltv/twitch/android/core/mvp/presenter/RxPresenter;",
                parameters = listOf(VIEW_DELEGATE), returnType = "V")),
        ).twitchMatch("clip feed menu")
        match.method.apply {
            val bind = match.instructionMatches.first()
            val call = bind.instruction as FiveRegisterInstruction
            if (call.registerCount != 2) throw PatchException("Twitch: unexpected clip feed menu binding")
            requireNotBranchTarget(bind.index + 1, "clip feed menu")
            addInstructions(bind.index + 1,
                invokeStatic("$CLIPS->overflowMenu(Ljava/lang/Object;Ljava/lang/Object;)V", call.registerC, call.registerD))
        }
        twitchSettings.add(SwitchSetting("clip_download", "Clip download",
            "Adds \"Download clip\" to a clip's menu (⋮) in the clips feed. Asks gql.twitch.tv for the clip's video " +
                "with the app's Client-ID only and saves it to Downloads/Twitch.",
            Section.PLAYBACK, default = false))
    }
}
