package io.github.jordanwoodson.patches.rumble.ads

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.ClassDef
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.enumConstant
import io.github.jordanwoodson.patches.rumble.shared.kotlinObject
import io.github.jordanwoodson.patches.shared.compose.parameters
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.rumble.shared.singleMethod
import io.github.jordanwoodson.patches.shared.compose.staticFieldReads
import io.github.jordanwoodson.patches.rumble.shared.unitReference
import io.github.jordanwoodson.patches.rumble.shorts.HomeSectionKindFingerprint
import io.github.jordanwoodson.patches.rumble.videolists.videoListsPatch
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.shared.invoke
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.addInstructionsIfEnabled

private const val KEY = "block_ads"

@Suppress("unused")
val blockAdsPatch = bytecodePatch(
    name = "Block ads",
    description = "Removes video ads (before and during videos and live streams), the ads in feeds and under " +
        "videos and Home's ad sections, signed in or not. The app never requests them, so it never sends the ad " +
        "server your advertising ID. Boosted (paid) videos are left out of Home, related videos, search and " +
        "categories, and no ad or Boosted video impressions or clicks are reported.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch, videoListsPatch)

    execute {
        val useCase = VideoAdUseCaseFingerprint.matchAllOrNull()?.singleOrNull()
            ?: throw PatchException("Rumble: expected exactly one video ad use case. " +
                "This patch targets Rumble ${RUMBLE.version}; recheck the ad code before updating compatibility.")
        val repositoryInterface = useCase.originalMethod.parameterTypes.first().toString()

        val implementations = mutableListOf<ClassDef>()
        classDefForEach { if (repositoryInterface in it.interfaces) implementations += it }
        val repository = mutableClassDefBy(implementations.singleOrNull()
            ?: throw PatchException("Rumble: expected one ad repository, found ${implementations.size}"))
        Locators.record("rumble.block_ads.repository", repository.type)

        // fetchVideoAds(publisher, ..., width: Float, height: Float, continuation): the pre-roll and
        // mid-roll list for a video or live stream, played through Google's IMA SDK.
        val fetchVideoAds = repository.singleMethod("video ad fetch") { method ->
            val parameters = method.parameters()
            parameters.size == 8 && parameters[0] == "Ljava/lang/String;" &&
                parameters[4] == "Ljava/lang/Long;" && parameters[5] == "F" && parameters[6] == "F"
        }
        // The two display ad fetches (feed and video page) take the targeting data: zone, keywords,
        // categories, user and video IDs, advertising ID and consent strings.
        val fetchDisplayAds = repository.methods.filter { it.parameterTypes.size >= 15 }
        if (fetchDisplayAds.size != 2) {
            throw PatchException("Rumble: expected two display ad fetches, found ${fetchDisplayAds.size}")
        }

        // Answer with the results Rumble already handles as "no ad": an empty video ad list, and
        // "waiting for ads", which the feed and video page treat like a failed request.
        fun returnObject(name: String) = """
            sget-object v0, ${kotlinObject(name)}
            return-object v0
        """
        fetchVideoAds.addInstructionsIfEnabled(KEY, returnObject("EmptyVideoAdList"))
        fetchDisplayAds.forEach { it.addInstructionsIfEnabled(KEY, returnObject("WaitingForAds")) }

        // reportImpression(url): the pixel Boosted (promoted) videos in feeds and ads fire when shown
        // (d4.ads.rmbl.ws). sendAdEvents(urls, time): click and progress beacons. Both report success.
        val reportImpression = repository.singleMethod("impression report") {
            it.parameters().dropLast(1) == listOf("Ljava/lang/String;")
        }
        val sendAdEvents = repository.singleMethod("ad event report") {
            it.parameters().dropLast(1) == listOf("Ljava/util/List;", "J")
        }
        reportImpression.addInstructionsIfEnabled(
            KEY,
            """
                sget-object v0, ${reportImpression.unitReference()}
                return-object v0
            """,
        )
        val eventsSent = sendAdEvents.staticFieldReads().singleOrNull { it.type == it.definingClass }
            ?: throw PatchException("Rumble: ad event report has no single success object")
        sendAdEvents.addInstructionsIfEnabled(
            KEY,
            """
                sget-object v0, $eventsSent
                return-object v0
            """,
        )

        // Boosted videos are paid placements: the server marks a video with the advertiser's creative and
        // click and impression URLs. The shared video list hook (RumbleInterface.videos) drops them from the lists
        // that carry them while this switch is on, and any left elsewhere shows as the plain video, without the
        // creative, the Boosted label and its reports.
        VideoBoostedFingerprint.single("Video.boosted getter").method.addInstructionsIfEnabled(
            KEY,
            """
                const/4 v0, 0x0
                return-object v0
            """,
        )

        // Home's ad_block sections hold a display ad. Treat them like sections the server hides, so they are
        // neither loaded nor drawn (with the display ads blocked, they would stay empty).
        val homeSectionKind = HomeSectionKindFingerprint.single("HomeSectionKind parser").method
        val hide = classDefBy(homeSectionKind.returnType).enumConstant("HIDE")
        homeSectionKind.apply {
            val kind = parameterRegister(1)
            addInstructionsIfEnabled(
                KEY,
                """
                    const-string v0, "ad_block"
                    ${invoke("invoke-virtual", "Ljava/lang/String;->equals(Ljava/lang/Object;)Z", 0, kind)}
                    move-result v0
                    if-eqz v0, :lurk_off
                    sget-object v0, $hide
                    return-object v0
                """,
            )
        }

        rumbleSettings.add(
            SwitchSetting(
                KEY,
                "Block ads",
                "Video, feed and video page ads, Boosted videos, Home's ad sections and ad impression reports.",
                Section.PRIVACY,
            ),
        )
    }
}
