package id.nearyou.app.resources

import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.account_separation_disclosure
import id.nearyou.resources.generated.resources.age_gate_dob_label
import id.nearyou.resources.generated.resources.age_gate_dob_picker_cta
import id.nearyou.resources.generated.resources.age_gate_explainer
import id.nearyou.resources.generated.resources.age_gate_title
import id.nearyou.resources.generated.resources.age_gate_under18_blocked
import id.nearyou.resources.generated.resources.app_name
import id.nearyou.resources.generated.resources.cta_cancel
import id.nearyou.resources.generated.resources.cta_close
import id.nearyou.resources.generated.resources.cta_continue
import id.nearyou.resources.generated.resources.cta_create_account
import id.nearyou.resources.generated.resources.cta_post
import id.nearyou.resources.generated.resources.cta_reply
import id.nearyou.resources.generated.resources.cta_retry
import id.nearyou.resources.generated.resources.cta_see_global
import id.nearyou.resources.generated.resources.cta_signin_google
import id.nearyou.resources.generated.resources.empty_state_generic
import id.nearyou.resources.generated.resources.error_generic
import id.nearyou.resources.generated.resources.home_placeholder_title
import id.nearyou.resources.generated.resources.home_placeholder_version
import id.nearyou.resources.generated.resources.loading
import id.nearyou.resources.generated.resources.location_consent_allow
import id.nearyou.resources.generated.resources.location_consent_body
import id.nearyou.resources.generated.resources.location_consent_title
import id.nearyou.resources.generated.resources.location_open_settings
import id.nearyou.resources.generated.resources.nearby_location_denied
import id.nearyou.resources.generated.resources.notif_chat_message
import id.nearyou.resources.generated.resources.notif_followed
import id.nearyou.resources.generated.resources.notif_generic
import id.nearyou.resources.generated.resources.notif_post_auto_hidden
import id.nearyou.resources.generated.resources.notif_post_liked
import id.nearyou.resources.generated.resources.notif_post_replied
import id.nearyou.resources.generated.resources.notifications_badge
import id.nearyou.resources.generated.resources.notifications_empty
import id.nearyou.resources.generated.resources.notifications_loading
import id.nearyou.resources.generated.resources.notifications_mark_all_read
import id.nearyou.resources.generated.resources.notifications_title
import id.nearyou.resources.generated.resources.post_create_char_counter
import id.nearyou.resources.generated.resources.post_create_content_placeholder
import id.nearyou.resources.generated.resources.post_create_error_empty
import id.nearyou.resources.generated.resources.post_create_error_location
import id.nearyou.resources.generated.resources.post_create_error_moderated
import id.nearyou.resources.generated.resources.post_create_error_too_long
import id.nearyou.resources.generated.resources.post_create_loading
import id.nearyou.resources.generated.resources.post_create_location_chip
import id.nearyou.resources.generated.resources.post_create_location_unavailable
import id.nearyou.resources.generated.resources.post_create_privacy_note
import id.nearyou.resources.generated.resources.post_create_title
import id.nearyou.resources.generated.resources.post_detail_like_count
import id.nearyou.resources.generated.resources.post_detail_likes_cap_upsell
import id.nearyou.resources.generated.resources.post_detail_post_gone
import id.nearyou.resources.generated.resources.post_detail_posted_from
import id.nearyou.resources.generated.resources.post_detail_posted_from_no_city
import id.nearyou.resources.generated.resources.post_detail_replies_empty
import id.nearyou.resources.generated.resources.post_detail_reply_cap_upsell
import id.nearyou.resources.generated.resources.post_detail_reply_counter
import id.nearyou.resources.generated.resources.post_detail_reply_placeholder
import id.nearyou.resources.generated.resources.post_detail_reset_hours
import id.nearyou.resources.generated.resources.profile_placeholder
import id.nearyou.resources.generated.resources.section_home
import id.nearyou.resources.generated.resources.section_home_icon_description
import id.nearyou.resources.generated.resources.section_notifications
import id.nearyou.resources.generated.resources.section_notifications_icon_description
import id.nearyou.resources.generated.resources.section_profile
import id.nearyou.resources.generated.resources.section_profile_icon_description
import id.nearyou.resources.generated.resources.signin_error_banned
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.signin_error_no_account
import id.nearyou.resources.generated.resources.signin_error_token_invalid
import id.nearyou.resources.generated.resources.signin_loading
import id.nearyou.resources.generated.resources.signin_screen_title
import id.nearyou.resources.generated.resources.signin_session_expired
import id.nearyou.resources.generated.resources.signup_error_account_exists
import id.nearyou.resources.generated.resources.signup_loading
import id.nearyou.resources.generated.resources.tab_following
import id.nearyou.resources.generated.resources.tab_following_icon_description
import id.nearyou.resources.generated.resources.tab_global
import id.nearyou.resources.generated.resources.tab_global_icon_description
import id.nearyou.resources.generated.resources.tab_nearby
import id.nearyou.resources.generated.resources.tab_nearby_icon_description
import id.nearyou.resources.generated.resources.timeline_empty_nearby
import id.nearyou.resources.generated.resources.timeline_following_placeholder
import id.nearyou.resources.generated.resources.timeline_global_title
import id.nearyou.resources.generated.resources.timeline_limit_hard
import id.nearyou.resources.generated.resources.timeline_limit_soft
import id.nearyou.resources.generated.resources.timeline_loading
import id.nearyou.resources.generated.resources.timeline_nearby_title
import id.nearyou.resources.generated.resources.timeline_session_redirect
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the `shared-resources` spec scenario "All Mobile #2 + #3 + #4 strings are declared"
 * (task 7.12). This list references every required `Res.string.*` accessor by name — the CMP
 * Resources codegen generates an accessor ONLY for a `<string>` present in
 * `composeResources/values/strings.xml`, so a missing or renamed key makes this file fail to
 * COMPILE (a stronger guard than a runtime check). The exact-text scenarios for the Mobile #4
 * additions are asserted in `AgeGateScreenTest.mobile4Strings_haveExactCanonicalText`.
 */
class SharedStringsCatalogTest {
    private val allDeclaredStrings =
        listOf(
            // Mobile #2 / #2.5 foundational
            Res.string.app_name,
            Res.string.error_generic,
            Res.string.cta_continue,
            Res.string.cta_cancel,
            Res.string.cta_retry,
            Res.string.cta_close,
            Res.string.loading,
            Res.string.empty_state_generic,
            Res.string.home_placeholder_title,
            Res.string.home_placeholder_version,
            // Mobile #3 sign-in
            Res.string.cta_signin_google,
            Res.string.signin_screen_title,
            Res.string.signin_error_no_account,
            Res.string.signin_error_banned,
            Res.string.signin_error_network,
            Res.string.signin_error_token_invalid,
            Res.string.signin_loading,
            Res.string.account_separation_disclosure,
            // Mobile #4 age-gate / signup
            Res.string.age_gate_title,
            Res.string.age_gate_explainer,
            Res.string.age_gate_dob_label,
            Res.string.age_gate_dob_picker_cta,
            Res.string.cta_create_account,
            Res.string.age_gate_under18_blocked,
            Res.string.signup_error_account_exists,
            Res.string.signup_loading,
            // Mobile #5 Nearby timeline (mobile-nearby-timeline-screen)
            Res.string.timeline_nearby_title,
            Res.string.timeline_loading,
            Res.string.timeline_empty_nearby,
            Res.string.timeline_limit_hard,
            Res.string.timeline_limit_soft,
            // mobile-session-expiry-and-proactive-refresh (2 net-new: the SignInScreen involuntary-logout
            // notice + the neutral terminal-401 redirect placeholder shared by Nearby + Global).
            Res.string.signin_session_expired,
            Res.string.timeline_session_redirect,
            // mobile-location-permission-flow (consent rationale + denial fallback + settings CTA)
            Res.string.location_consent_title,
            Res.string.location_consent_body,
            Res.string.location_consent_allow,
            Res.string.nearby_location_denied,
            Res.string.location_open_settings,
            // mobile-post-creation-screen (composer title/placeholder/counter/CTA/loading + per-error
            // banners). The 3 reused strings (signin_error_network, cta_retry, location_open_settings)
            // are already counted above; these 10 are net-new.
            Res.string.post_create_title,
            Res.string.post_create_content_placeholder,
            Res.string.post_create_char_counter,
            Res.string.cta_post,
            Res.string.post_create_loading,
            Res.string.post_create_error_empty,
            Res.string.post_create_error_too_long,
            Res.string.post_create_error_location,
            Res.string.post_create_error_moderated,
            Res.string.post_create_location_unavailable,
            Res.string.post_create_location_chip,
            Res.string.post_create_privacy_note,
            // mobile-home-tab-host + mobile-global-timeline (3 tab labels + 3 tab icon content
            // descriptions + the Global title + the Following placeholder + the "lihat Global" CTA).
            // The Global empty/error states reuse timeline_loading / signin_error_network / cta_retry
            // (already counted above) — no new keys for those.
            Res.string.tab_nearby,
            Res.string.tab_following,
            Res.string.tab_global,
            Res.string.tab_nearby_icon_description,
            Res.string.tab_following_icon_description,
            Res.string.tab_global_icon_description,
            Res.string.timeline_global_title,
            Res.string.timeline_following_placeholder,
            Res.string.cta_see_global,
            // mobile-bottom-nav-sections-and-notifications (3 section labels + 3 section icon content
            // descriptions + the Profil placeholder + 5 notifications-screen keys + 6 type-keyed
            // generic-actor notification copy). The notifications error/retry states reuse
            // signin_error_network / cta_retry (already counted above), and the Home section reuses the
            // feed tab labels + cta_post — no new keys for those.
            Res.string.section_home,
            Res.string.section_notifications,
            Res.string.section_profile,
            Res.string.section_home_icon_description,
            Res.string.section_notifications_icon_description,
            Res.string.section_profile_icon_description,
            Res.string.profile_placeholder,
            Res.string.notifications_title,
            Res.string.notifications_loading,
            Res.string.notifications_empty,
            Res.string.notifications_mark_all_read,
            Res.string.notifications_badge,
            Res.string.notif_post_liked,
            Res.string.notif_post_replied,
            Res.string.notif_followed,
            Res.string.notif_post_auto_hidden,
            Res.string.notif_chat_message,
            Res.string.notif_generic,
            // mobile-post-detail-screen (post header + like control + replies list + reply composer).
            // 11 net-new keys (the empty-`city_name` header gets its own `post_detail_posted_from_no_city`
            // variant; `post_detail_post_gone` is the terminal-404 banner). The replies-loading +
            // screen-loading states reuse `timeline_loading`; the generic error state reuses
            // `signin_error_network` + `cta_retry` (already counted above) — no new keys for those.
            Res.string.post_detail_posted_from,
            Res.string.post_detail_posted_from_no_city,
            Res.string.post_detail_like_count,
            Res.string.post_detail_reset_hours,
            Res.string.post_detail_likes_cap_upsell,
            Res.string.post_detail_replies_empty,
            Res.string.post_detail_reply_placeholder,
            Res.string.post_detail_reply_counter,
            Res.string.post_detail_reply_cap_upsell,
            Res.string.post_detail_post_gone,
            Res.string.cta_reply,
        )

    @Test
    fun `all Mobile 2 3 4 and 5 plus location and post-creation string keys are declared`() {
        // 10 (Mobile #2/#2.5) + 8 (Mobile #3) + 8 (Mobile #4) + 5 (Mobile #5 timeline)
        // + 5 (mobile-location-permission-flow) + 10 (mobile-post-creation-screen)
        // + 9 (mobile-home-tab-host: 3 tab labels + 3 tab icon descriptions + Global title
        // + Following placeholder + lihat-Global CTA)
        // + 18 (mobile-bottom-nav-sections-and-notifications: 3 section labels + 3 section icon
        // descriptions + Profil placeholder + 5 notifications-screen keys + 6 type-keyed copy)
        // + 11 (mobile-post-detail-screen: posted-from + no-city variant + like-count + reset-hours
        // countdown fragment + like-cap upsell + replies-empty + reply-placeholder + reply-counter
        // + reply-cap upsell + post-gone banner + Balas CTA; the loading/generic-error states reuse
        // timeline_loading / signin_error_network / cta_retry) = 84.
        // + 2 (mobile-session-expiry-and-proactive-refresh: signin_session_expired + timeline_session_redirect) = 86.
        // + 2 (mobile-mockup-visual-conformance: post_create_location_chip + post_create_privacy_note) = 88.
        assertEquals(88, allDeclaredStrings.size)
        assertEquals(allDeclaredStrings.size, allDeclaredStrings.distinct().size, "no duplicate accessors")
    }
}
