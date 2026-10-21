package io.github.jordanwoodson.patches.kick.clips

import app.morphe.patcher.patch.bytecodePatch
import io.github.jordanwoodson.patches.kick.settings.kickSettings
import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.kick.shared.HermesOp
import io.github.jordanwoodson.patches.kick.shared.hex
import io.github.jordanwoodson.patches.kick.shared.kickHermes
import io.github.jordanwoodson.patches.kick.shared.kickHermesPatch
import io.github.jordanwoodson.patches.shared.settings.Section
import io.github.jordanwoodson.patches.shared.settings.SwitchSetting

private const val KEY = "hide_clips"

@Suppress("unused")
val hideClipsPatch = bytecodePatch(
    name = "Hide Clips",
    description = "Hides Clips tabs in Browse, channels and categories, and the popular-clips row on channels. " +
        "Shared clip links still open.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_KICK)
    dependsOn(kickHermesPatch)

    execute {
        // BrowseScreenLayout's memoized screen list: categories, livestreams, clips. Select the
        // anonymous producer by its three tab titles, not its function ID.
        val browseTabs = kickHermes.functionUsing("Browse tab list",
            loads = listOf("categories.title.message", "browse.tabs.livechannels.title.message", "clips.title.message"))
        kickHermes.replace(browseTabs, KEY, hex("09 01 03 00 5a 01 02 00"), hex("09 01 02 00 5a 01 02 00"))
        kickHermes.replace(browseTabs, KEY, hex("5a 01 02 02 76 01"), hex("93 00 93 00 76 01"))

        // ChannelLayout's list: home, about, videos, clips, chat. Remove the Clips append and
        // compact Chat into index 3. r0 already holds undefined throughout both small producers.
        val channelTabs = kickHermes.functionUsing("channel tab list",
            loads = listOf("channel.tabs.home.message", "channel.tabs.clips.message", "channel.tabs.chat.message"))
        kickHermes.replace(channelTabs, KEY, hex("09 01 05 00 5a 01 02 00"), hex("09 01 04 00 5a 01 02 00"))
        kickHermes.replace(channelTabs, KEY, hex("52 02 03 01 5a 01 02 03"), hex("52 02 03 01 93 00 93 00"))
        kickHermes.replace(channelTabs, KEY, hex("5a 01 02 04 76 01"), hex("5a 01 02 03 76 01"))

        // The category header's screen list has Live and Clips. The existing r1 is undefined;
        // restoring it twice is a harmless, complete four-byte replacement for the Clips append.
        kickHermes.replace(
            "BrowseCategoriesHeader", KEY,
            hex("09 0c 02 00 5a 0c 10 00 5a 0c 15 01"),
            hex("09 0c 01 00 5a 0c 10 00 93 01 93 01"),
        )

        // Remove the PopularClipsRow React element in its parent, preserving the video and
        // recent-category rows: jsx(PopularClipsRow, {channelSlug}) becomes null in the same register.
        // React ignores null children; there is no blank Clips screen.
        val (home, row) = kickHermes.sequence(
            "PopularClipsRow element", kickHermes.functionsNamed("ChannelHomeContent"),
            listOf(
                kickHermes.reads("PopularClipsRow"),
                kickHermes.creates("channelSlug"),
                { it.opcode == HermesOp.PUT_OWN_BY_SLOT_IDX },
                { it.opcode == HermesOp.CALL3 },
            ),
        ) { (component, props, put, call) ->
            put.u8(1) == props.u8(1) && call.u8(4) == component.u8(1) && call.u8(5) == props.u8(1)
        }
        val element = row.last()
        val result = element.bytes[1]
        val loadNull = HermesOp.LOAD_CONST_NULL.toByte()
        kickHermes.replaceInstruction(home, KEY, element, byteArrayOf(loadNull, result, loadNull, result, loadNull, result))
        kickSettings.add(
            SwitchSetting(
                KEY,
                "Hide Clips",
                "Hide Clips tabs and the channel clips row. Shared clip links still open.",
                Section.INTERFACE,
                restart = true,
            ),
        )
    }
}
