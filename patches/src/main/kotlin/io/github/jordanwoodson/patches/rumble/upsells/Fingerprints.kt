package io.github.jordanwoodson.patches.rumble.upsells

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import io.github.jordanwoodson.patches.rumble.shared.composableFingerprint
import io.github.jordanwoodson.patches.shared.compose.dtoGetterFingerprint

// Decides whether the video page opens the "Rumble Premium" sheet, after a delay set in Remote Config.
internal object PremiumPromoFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    filters = listOf(string("premium_promo_display_delay_days")),
)

// "Rumble Premium: Upgrade Now" on Home and My Feed.
internal val PremiumBannerFingerprint =
    composableFingerprint("com.rumble.battles.feed.presentation.views.PremiumBannerView")

// "Special Premium Offer" on the account screen.
internal val PremiumOfferBannerFingerprint =
    composableFingerprint("com.rumble.battles.premium.presentation.views.PremiumOfferBanner")

// The Premium pitch shown instead of the downloads list.
internal val DownloadsUpsellFingerprint =
    composableFingerprint("com.rumble.battles.download.presentation.DownloadsUpsellView")

// Live chat's "gift Rumble Premium" list.
internal val PremiumGiftsFingerprint = dtoGetterFingerprint(
    "Lcom/rumble/network/dto/livechat/LiveChatConfig;",
    "giftList",
    "Lcom/rumble/network/dto/livechat/PremiumGiftList;",
)
