package io.github.jordanwoodson.patches.rumble.videopage

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.rumble.videolists.videoListsPatch
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.shared.compose.dtoGetterFingerprint
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.addInstructionsIfEnabled

private const val COMMENTS = "hide_comments"
private const val RELATED = "hide_related_videos"

// Video.areCommentsDisabled: true makes the video page leave out its Comments button, as for a video whose
// creator turned comments off.
private val CommentsDisabledFingerprint =
    dtoGetterFingerprint("Lcom/rumble/network/dto/video/Video;", "areCommentsDisabled", "Ljava/lang/Boolean;")

@Suppress("unused")
val hideCommentsRelatedPatch = bytecodePatch(
    name = "Hide comments and related videos",
    description = "Hides the comments under videos, and the related videos the video page lists, each with its own " +
        "switch. Without related videos, Autoplay next video has nothing to play. Starts off. Turn them on in " +
        "Lurk settings > Interface.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch, videoListsPatch)

    execute {
        CommentsDisabledFingerprint.single("Video.areCommentsDisabled getter").method.apply {
            Locators.record("rumble.hide_comments.comments_disabled", this)
            addInstructionsIfEnabled(
                COMMENTS,
                """
                    sget-object v0, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                    return-object v0
                """,
            )
        }

        // The related video lists (RelatedVideoData.videos, Video.related) go through the shared video list hook,
        // RumbleInterface.videos, which empties them while the switch is on.

        rumbleSettings.add(
            SwitchSetting(COMMENTS, "Hide comments", "The Comments button under videos, as when a creator turns " +
                "comments off.", Section.INTERFACE, default = false),
            SwitchSetting(RELATED, "Hide related videos",
                "The related videos on the video page. Autoplay next video then has nothing to play.",
                Section.INTERFACE, default = false),
        )
    }
}
