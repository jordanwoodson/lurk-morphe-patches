package io.github.jordanwoodson.patches.rumble.videolists

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE_INTERFACE
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.shared.compose.dtoGetterFingerprint
import io.github.jordanwoodson.patches.shared.invokeStatic

private const val DTO = "Lcom/rumble/network/dto"
private const val LIST = "Ljava/util/List;"

/** What a list is, for RumbleInterface.videos: any list of videos, or a video's related videos. */
private const val LIST_KIND = 0
private const val RELATED_KIND = 1

// The lists of videos the server sends (Gson DTOs, found by field name): Home sections, the related endpoint's
// list and a video's own related videos, search results and category pages.
private val VIDEO_LISTS = listOf(
    Triple("Home section videos", dtoGetterFingerprint("$DTO/home/HomeSectionDto;", "videos", LIST), LIST_KIND),
    Triple("related videos", dtoGetterFingerprint("$DTO/video/RelatedVideoData;", "videos", LIST), RELATED_KIND),
    Triple("video related", dtoGetterFingerprint("$DTO/video/Video;", "related", LIST), RELATED_KIND),
    Triple("search videos", dtoGetterFingerprint("$DTO/search/VideoItems;", "items", LIST), LIST_KIND),
    Triple("category videos", dtoGetterFingerprint("$DTO/discover/CategoryVideoListData;", "items", LIST), LIST_KIND),
)

/**
 * Passes the server's video lists through RumbleInterface.videos, once, for the patches that leave videos out:
 * Block ads (Boosted videos), Hide premium-only videos and Hide comments and related videos.
 */
internal val videoListsPatch = bytecodePatch(
    description = "Lets the Rumble patches leave videos out of lists.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        VIDEO_LISTS.forEach { (what, fingerprint, kind) ->
            fingerprint.single("$what getter").method.apply {
                if (implementation!!.instructions.first().opcode != Opcode.IGET_OBJECT) {
                    throw RUMBLE.error("unexpected $what getter")
                }
                // iget-object v0, p0 (= v1): after the read only v0 is used, so v1 is free for the kind.
                val list = getInstruction<OneRegisterInstruction>(0).registerA
                if (list != 0 || implementation!!.registerCount < 2) {
                    throw RUMBLE.error("unexpected registers in the $what getter")
                }
                addInstructions(
                    1,
                    """
                        const/4 v1, $kind
                        ${invokeStatic("$RUMBLE_INTERFACE->videos(Ljava/util/List;I)Ljava/util/List;", 0, 1)}
                        move-result-object v0
                    """,
                )
            }
        }
    }
}
