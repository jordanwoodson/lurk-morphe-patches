package io.github.jordanwoodson.patches.rumble.search

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import io.github.jordanwoodson.patches.rumble.settings.rumbleSettings
import io.github.jordanwoodson.patches.rumble.settings.settingsPatch
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.shared.Locators
import io.github.jordanwoodson.patches.rumble.shared.constructorParameterType
import io.github.jordanwoodson.patches.rumble.shared.single
import io.github.jordanwoodson.patches.rumble.shared.unitReference
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting
import io.github.jordanwoodson.patches.shared.settings.addInstructionsIfEnabled

private const val KEY = "no_search_history"

@Suppress("unused")
val dontSaveSearchesPatch = bytecodePatch(
    name = "Don't save searches",
    description = "Stops Rumble from keeping your searches on the phone and hides the ones it kept, so they no " +
        "longer show under the search box. Suggestions still come from Rumble's server as you type. On by " +
        "default. Turn it off in Lurk settings > Privacy.",
) {
    compatibleWith(COMPATIBILITY_RUMBLE)
    dependsOn(settingsPatch)

    execute {
        val constructor = SearchViewModelConstructorFingerprint.single("SearchViewModel constructor").method

        // Saving a search and updating its time return Unit, as after a write.
        listOf("saveQueryUseCase" to "save", "updateQueryUseCase" to "update").forEach { (name, locator) ->
            useCase(constructor, name).apply {
                Locators.record("rumble.no_search_history.$locator", this)
                addInstructionsIfEnabled(
                    KEY,
                    """
                        sget-object v0, ${unitReference()}
                        return-object v0
                    """,
                )
            }
        }
        // The recent searches under the search box (and the ones filtered as you type) are empty. The table
        // keeps its rows, so turning the switch off brings them back.
        useCase(constructor, "getRecentQueriesUseCase").also {
            Locators.record("rumble.no_search_history.recent", it)
        }.addInstructionsIfEnabled(
            KEY,
            """
                invoke-static { }, Ljava/util/Collections;->emptyList()Ljava/util/List;
                move-result-object v0
                return-object v0
            """,
        )

        rumbleSettings.add(
            SwitchSetting(
                KEY,
                "Don't save searches",
                "Searches aren't kept on the phone, and the ones Rumble kept don't show. Clear all in Rumble's " +
                    "search deletes those for good.",
                Section.PRIVACY,
                default = true,
            ),
        )
    }
}

/** The one suspend method of the use case the constructor takes as [name]. */
private fun BytecodePatchContext.useCase(constructor: MutableMethod, name: String): MutableMethod {
    val type = constructor.constructorParameterType(name)
    val methods = mutableClassDefBy(type).methods.filter {
        it.name != "<init>" && !AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
            it.returnType == "Ljava/lang/Object;"
    }
    return methods.singleOrNull()
        ?: throw PatchException("Rumble: expected one public suspend method in $name ($type), found ${methods.size}")
}
