package io.github.jordanwoodson.patches.rumble.analytics

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE_PRIVACY
import io.github.jordanwoodson.patches.rumble.shared.kotlinObject
import io.github.jordanwoodson.patches.shared.compose.newInstanceTypes
import io.github.jordanwoodson.patches.shared.compose.parameters
import io.github.jordanwoodson.patches.rumble.shared.singleMethod
import io.github.jordanwoodson.patches.shared.compose.staticFieldReads
import io.github.jordanwoodson.patches.rumble.shared.unitReference
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.addInstructionsIfEnabled
import io.github.jordanwoodson.patches.shared.settings.isEnabled

private const val STRING = "Ljava/lang/String;"
private const val KEY = "disable_analytics"
private const val KEEP_WATCH_PROGRESS_KEY = "keep_watch_progress"

@Suppress("unused")
val disableAnalyticsPatch = bytecodePatch(
    name = "Disable analytics",
    description = "Stops Rumble's own analytics, which it sends whatever the consent setting: clicks, " +
        "impressions, searches, watch progress and player state, with your user ID, username, advertising ID " +
        "and AppsFlyer ID, to e.rumble.com and its debug log. Signed-in watch history may stop updating " +
        "unless watch progress is kept.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    val keepWatchProgress by booleanOption(
        key = "keepWatchProgress",
        default = false,
        title = "Keep watch progress",
        description = "Keep sending watch progress events (video, position and player state), and only those. " +
            "They carry your user ID, advertising ID and AppsFlyer ID. Rumble may use them for your watch history " +
            "and for creators' view counts. Can be changed later in Lurk settings > Privacy.",
    )

    execute {
        val repository = EventRepositoryFingerprint.matchAllOrNull()?.singleOrNull()?.classDef
            ?: throw PatchException("Rumble: expected exactly one event repository. " +
                "This patch targets Rumble ${RUMBLE.version}; recheck the analytics code before updating compatibility.")

        // send(events: List, ...6 strings, continuation): every e.rumble.com upload.
        val sendEvents = repository.singleMethod("event upload") { method ->
            val parameters = method.parameters()
            parameters.size == 8 && parameters[0] == "Ljava/util/List;" &&
                parameters.subList(1, 7).all { it == STRING }
        }
        // Search result clicks and the debug log copies of AppsFlyer and Firebase events.
        val sendSearchClick = repository.singleMethod("search click upload") {
            it.parameters().dropLast(1) == listOf(STRING, STRING, "J", STRING)
        }
        val sendDebugEvent = repository.singleMethod("debug log upload") {
            it.parameters().dropLast(1) == List(6) { STRING } + "Ljava/util/Map;"
        }

        // Callers check the upload's result. Report Success, as after a real upload: the watch time
        // uploader then clears its queue instead of retrying.
        val upload = classDefBy(sendEvents.newInstanceTypes().singleOrNull()
            ?: throw PatchException("Rumble: event upload has no single coroutine"))
        val success = kotlinObject(
            "Success",
            among = upload.methods.flatMap { it.staticFieldReads() }.map { it.definingClass }.toSet(),
        )
        val watchProgress = WatchProgressEventToStringFingerprint.matchAllOrNull()?.singleOrNull()?.classDef?.type
            ?: throw PatchException("Rumble: expected exactly one WatchProgressEvent class")
        Locators.record("rumble.disable_analytics.repository", repository.type)
        Locators.record("rumble.disable_analytics.watch_progress_event", watchProgress)

        // Rumble batches watch progress with the other events. While it's kept, only the watch progress events
        // of a batch go out; a batch without any is answered with Success. The method hands its arguments
        // straight to a coroutine (no state machine), so replacing the list parameter is safe.
        val events = sendEvents.parameterRegister(1)
        sendEvents.addInstructionsIfEnabled(
            KEY,
            """
                ${isEnabled(KEEP_WATCH_PROGRESS_KEY, "v0")}
                if-eqz v0, :skip
                move-object/from16 v0, p1
                const-class v1, $watchProgress
                ${invokeStatic("$RUMBLE_PRIVACY->watchProgressOnly(Ljava/util/List;Ljava/lang/Class;)Ljava/util/List;", 0, 1)}
                move-result-object v0
                if-eqz v0, :skip
                move-object/16 v$events, v0
                goto :lurk_off
                :skip
                sget-object v0, $success
                return-object v0
            """,
            locals = 2,
        )

        listOf(sendSearchClick, sendDebugEvent).forEach { method ->
            method.addInstructionsIfEnabled(
                KEY,
                """
                    sget-object v0, ${method.unitReference()}
                    return-object v0
                """,
            )
        }

        rumbleSettings.add(
            SwitchSetting(
                KEY,
                "Disable analytics",
                "Stops Rumble's own event uploads to e.rumble.com and its debug log.",
                Section.PRIVACY,
            ),
            SwitchSetting(
                KEEP_WATCH_PROGRESS_KEY,
                "Keep watch progress",
                "Still send watch progress events, and only those, which Rumble may use for your watch history. " +
                    "They carry your user ID, advertising ID and AppsFlyer ID.",
                Section.PRIVACY,
                default = keepWatchProgress == true,
                parent = KEY,
            ),
        )
    }
}
