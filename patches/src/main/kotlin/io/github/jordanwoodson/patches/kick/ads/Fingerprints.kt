package io.github.jordanwoodson.patches.kick.ads

import app.morphe.patcher.Fingerprint
import io.github.jordanwoodson.patches.kick.player.PLAYER_VIEW

// Kick's own Kotlin player view keeps its name and its methods' names.

internal const val MUX_AD_EVENT = "Lcom/kick/mobile/kickivsplayerv2/MuxAdEventData;"

/** The constructor's lambda that hands each MediaTailor ad event the Datazoom manager forwards to Mux. */
internal object MediaTailorAdEventFingerprint : Fingerprint(
    definingClass = PLAYER_VIEW, returnType = "Lkotlin/Unit;", parameters = listOf(PLAYER_VIEW, MUX_AD_EVENT),
)

internal object PlayerViewSetMutedFingerprint : Fingerprint(
    definingClass = PLAYER_VIEW, name = "setMuted", returnType = "V", parameters = listOf("Z"),
)

internal object PlayerViewStreamUrlFingerprint : Fingerprint(
    definingClass = PLAYER_VIEW, name = "setStreamUrl", returnType = "V", parameters = listOf("Ljava/lang/String;"),
)

internal object PlayerViewLoadSourceFingerprint : Fingerprint(
    definingClass = PLAYER_VIEW, name = "loadSource", returnType = "V", parameters = listOf("I"),
)

internal object PlayerViewDestroyFingerprint : Fingerprint(
    definingClass = PLAYER_VIEW, name = "destroy", returnType = "V", parameters = emptyList(),
)
