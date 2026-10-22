package io.github.jordanwoodson.patches.shared.apps

import io.github.jordanwoodson.patches.kick.shared.Constants.COMPATIBILITY_KICK
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.twitch.shared.Constants.COMPATIBILITY_TWITCH

// The apps as the shared patches see them (see LurkApp). Each app's own patches stay in its folder.

internal val TWITCH = LurkApp(
    slug = "twitch",
    compatibility = COMPATIBILITY_TWITCH,
)

internal val KICK = LurkApp(
    slug = "kick",
    compatibility = COMPATIBILITY_KICK,
)

internal val RUMBLE = LurkApp(
    slug = "rumble",
    compatibility = COMPATIBILITY_RUMBLE,
)
