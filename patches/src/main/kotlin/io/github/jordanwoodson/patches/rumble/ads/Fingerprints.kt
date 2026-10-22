package io.github.jordanwoodson.patches.rumble.ads

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import io.github.jordanwoodson.patches.shared.compose.dtoGetterFingerprint

// The video ad use case. Its constructor takes Rumble's ad repository (an interface) first and checks
// its parameters by name; the repository's single implementation is what the patch changes.
internal object VideoAdUseCaseFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    strings = listOf("rumbleAdRepository", "getCustomAdTag", "createPreRollVideoIdUseCase"),
)

private const val DTO = "Lcom/rumble/network/dto"
private const val LIST = "Ljava/util/List;"

// A video's Boosted data: the advertiser's thumbnail and title, the bid, and the click and impression URLs.
internal val VideoBoostedFingerprint =
    dtoGetterFingerprint("$DTO/video/Video;", "boosted", "$DTO/video/Boosted;")

// The lists of videos the server sends, which may hold Boosted videos: Home sections, related videos (two
// DTOs), search results and category pages.
internal val VideoListFingerprints = listOf(
    "Home section videos" to dtoGetterFingerprint("$DTO/home/HomeSectionDto;", "videos", LIST),
    "related videos" to dtoGetterFingerprint("$DTO/video/RelatedVideoData;", "videos", LIST),
    "video related" to dtoGetterFingerprint("$DTO/video/Video;", "related", LIST),
    "search videos" to dtoGetterFingerprint("$DTO/search/VideoItems;", "items", LIST),
    "category videos" to dtoGetterFingerprint("$DTO/discover/CategoryVideoListData;", "items", LIST),
)
