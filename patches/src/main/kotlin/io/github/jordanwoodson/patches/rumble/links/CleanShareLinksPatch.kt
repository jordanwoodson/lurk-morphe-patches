package io.github.jordanwoodson.patches.rumble.links

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.RUMBLE_PRIVACY
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.shared.invokeStatic
import io.github.jordanwoodson.patches.shared.parameterRegister
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.addInstructionsIfEnabled

private const val KEY = "clean_share_links"

@Suppress("unused")
val cleanShareLinksPatch = bytecodePatch(
    name = "Clean share links",
    description = "Removes everything after the ? from Rumble links before they are shared, so tracking tags such " +
        "as e9s (where you found the video) can't go out with them. Rumble ${RUMBLE.version} shares clean links; " +
        "this keeps them clean if the server starts adding tags.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        // share(text, title): the text is the link. Only rumble.com links (and its subdomains) are changed.
        ShareTextFingerprint.single("share use case").method.apply {
            val text = parameterRegister(1)
            addInstructionsIfEnabled(
                KEY,
                """
                    ${invokeStatic("$RUMBLE_PRIVACY->cleanShareLink(Ljava/lang/String;)Ljava/lang/String;", text)}
                    move-result-object v0
                    move-object/16 v$text, v0
                """,
            )
        }

        rumbleSettings.add(
            SwitchSetting(KEY, "Clean share links", "Removes the query from rumble.com links you share.", Section.PRIVACY),
        )
    }
}
