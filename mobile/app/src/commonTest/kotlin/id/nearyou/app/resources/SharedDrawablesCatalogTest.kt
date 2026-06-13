package id.nearyou.app.resources

import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.ic_action_chat
import id.nearyou.resources.generated.resources.ic_action_compose
import id.nearyou.resources.generated.resources.ic_nav_home
import id.nearyou.resources.generated.resources.ic_nav_home_filled
import id.nearyou.resources.generated.resources.ic_nav_notifications
import id.nearyou.resources.generated.resources.ic_nav_notifications_filled
import id.nearyou.resources.generated.resources.ic_nav_profile
import id.nearyou.resources.generated.resources.ic_nav_profile_filled
import id.nearyou.resources.generated.resources.ic_post_like
import id.nearyou.resources.generated.resources.ic_post_like_filled
import id.nearyou.resources.generated.resources.ic_post_location
import id.nearyou.resources.generated.resources.ic_post_reply
import id.nearyou.resources.generated.resources.ic_privacy_shield
import org.jetbrains.compose.resources.DrawableResource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the `shared-resources` spec scenario "Navigation, action, and card icon drawables exist and
 * are accessible via CMP Resources" (mobile-home-shell-redesign task 2.1 / D4). The CMP Resources
 * codegen generates a `Res.drawable.*` accessor ONLY for a vector drawable present in
 * `composeResources/drawable/`, so a missing or renamed asset makes this file fail to COMPILE (a
 * stronger guard than a runtime check) — exactly as `SharedStringsCatalogTest` guards the string
 * catalog. Covers the bottom-nav (Home / Notifications / Profile, outlined + filled), the composer
 * action (add), the composer privacy-note shield (verified_user, mobile-mockup-visual-conformance),
 * and the post-card affordances (location / like outlined + filled / reply).
 * NO feed-tab icon drawable is required — the feed tabs are text-only (D10).
 */
class SharedDrawablesCatalogTest {
    private val allDeclaredDrawables: List<DrawableResource> =
        listOf(
            // Bottom-nav (outlined + filled per the M3 unselected/selected convention)
            Res.drawable.ic_nav_home,
            Res.drawable.ic_nav_home_filled,
            Res.drawable.ic_nav_notifications,
            Res.drawable.ic_nav_notifications_filled,
            Res.drawable.ic_nav_profile,
            Res.drawable.ic_nav_profile_filled,
            // Composer action (icon-only FAB)
            Res.drawable.ic_action_compose,
            // Home app-bar "Pesan" action (mobile-chat-screen)
            Res.drawable.ic_action_chat,
            // Composer privacy-note shield (verified_user)
            Res.drawable.ic_privacy_shield,
            // Post-card affordances
            Res.drawable.ic_post_location,
            Res.drawable.ic_post_like,
            Res.drawable.ic_post_like_filled,
            Res.drawable.ic_post_reply,
        )

    @Test
    fun `all Material icon drawables for nav action and card affordances are declared`() {
        // 6 bottom-nav (3 destinations x outlined/filled) + 1 composer add + 1 chat action
        // + 1 privacy shield + 4 post-card affordances (location + like outlined/filled + reply)
        // = 13. No feed-tab icon (tabs are text-only). The card-time clock glyph (ic_post_time) was
        // dropped by mobile-timeline-card-redesign — card time renders as text in the identity header.
        assertEquals(13, allDeclaredDrawables.size)
        assertEquals(allDeclaredDrawables.size, allDeclaredDrawables.distinct().size, "no duplicate accessors")
    }
}
