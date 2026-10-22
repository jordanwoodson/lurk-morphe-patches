package io.github.jordanwoodson.patches.rumble.tipping

import app.morphe.patcher.Fingerprint
import io.github.jordanwoodson.patches.shared.compose.dtoGetterFingerprint

// The channel action buttons (Follow, Join, Tip, Shop), drawn from a list of the button types to show.
internal object CreatorActionButtonsFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("possibleCreatorActionButtonTypesToDisplay", "onJoin"),
)

// A creator's crypto tip details; the Tip button needs them.
internal val CreatorTipsInfoFingerprint = dtoGetterFingerprint(
    "Lcom/rumble/network/dto/creator/Creator;",
    "tipsInfo",
    "Lcom/rumble/network/dto/creator/TipsInfo;",
)

// The video uploader's Rumble Wallet, which the tip button on Shorts uses.
internal val VideoSourceWalletFingerprint = dtoGetterFingerprint(
    "Lcom/rumble/network/dto/video/VideoSource;",
    "wallet",
    "Lcom/rumble/network/dto/creator/Wallet;",
)

// Live chat Rants (paid messages): whether they are on, and whether Rumble Wallet can pay for them.
internal val RantsEnabledFingerprint = dtoGetterFingerprint(
    "Lcom/rumble/network/dto/livechat/LiveChatConfigRant;",
    "enable",
    "Ljava/lang/Boolean;",
)

internal val RantsWalletFingerprint = dtoGetterFingerprint(
    "Lcom/rumble/network/dto/livechat/LiveChatConfigRant;",
    "walletEnabled",
    "Z",
)
