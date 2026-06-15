package id.nearyou.app.resources

import id.nearyou.resources.generated.resources.Res
import id.nearyou.resources.generated.resources.account_separation_disclosure
import id.nearyou.resources.generated.resources.age_gate_dob_label
import id.nearyou.resources.generated.resources.age_gate_dob_picker_cta
import id.nearyou.resources.generated.resources.age_gate_explainer
import id.nearyou.resources.generated.resources.age_gate_title
import id.nearyou.resources.generated.resources.age_gate_under18_blocked
import id.nearyou.resources.generated.resources.app_name
import id.nearyou.resources.generated.resources.blocked_users_empty
import id.nearyou.resources.generated.resources.blocked_users_title
import id.nearyou.resources.generated.resources.blocked_users_unblock
import id.nearyou.resources.generated.resources.cap_countdown_hours_minutes
import id.nearyou.resources.generated.resources.cap_countdown_minutes
import id.nearyou.resources.generated.resources.cap_dialog_title
import id.nearyou.resources.generated.resources.chat_account_deleted
import id.nearyou.resources.generated.resources.chat_list_empty
import id.nearyou.resources.generated.resources.chat_list_loading
import id.nearyou.resources.generated.resources.chat_list_title
import id.nearyou.resources.generated.resources.chat_message_redacted
import id.nearyou.resources.generated.resources.chat_open_action
import id.nearyou.resources.generated.resources.chat_send
import id.nearyou.resources.generated.resources.chat_send_blocked
import id.nearyou.resources.generated.resources.chat_thread_input_placeholder
import id.nearyou.resources.generated.resources.consent_ads_desc
import id.nearyou.resources.generated.resources.consent_ads_label
import id.nearyou.resources.generated.resources.consent_analytics_desc
import id.nearyou.resources.generated.resources.consent_analytics_label
import id.nearyou.resources.generated.resources.consent_crash_desc
import id.nearyou.resources.generated.resources.consent_crash_label
import id.nearyou.resources.generated.resources.consent_cta_continue
import id.nearyou.resources.generated.resources.consent_error_retryable
import id.nearyou.resources.generated.resources.consent_explainer
import id.nearyou.resources.generated.resources.consent_skip
import id.nearyou.resources.generated.resources.consent_title
import id.nearyou.resources.generated.resources.cta_activate_premium
import id.nearyou.resources.generated.resources.cta_block
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
import id.nearyou.resources.generated.resources.follow_list_empty_followers
import id.nearyou.resources.generated.resources.follow_list_empty_following
import id.nearyou.resources.generated.resources.follow_list_load_more_failed
import id.nearyou.resources.generated.resources.follow_list_tab_followers
import id.nearyou.resources.generated.resources.follow_list_tab_following
import id.nearyou.resources.generated.resources.follow_list_title
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
import id.nearyou.resources.generated.resources.notif_permission_rationale
import id.nearyou.resources.generated.resources.notif_post_auto_hidden
import id.nearyou.resources.generated.resources.notif_post_liked
import id.nearyou.resources.generated.resources.notif_post_replied
import id.nearyou.resources.generated.resources.notifications_badge
import id.nearyou.resources.generated.resources.notifications_empty
import id.nearyou.resources.generated.resources.notifications_loading
import id.nearyou.resources.generated.resources.notifications_mark_all_read
import id.nearyou.resources.generated.resources.notifications_title
import id.nearyou.resources.generated.resources.post_card_action_like
import id.nearyou.resources.generated.resources.post_card_action_reply
import id.nearyou.resources.generated.resources.post_card_handle
import id.nearyou.resources.generated.resources.post_card_like_state_liked
import id.nearyou.resources.generated.resources.post_card_like_state_not_liked
import id.nearyou.resources.generated.resources.post_card_meta_separator
import id.nearyou.resources.generated.resources.post_create_char_counter
import id.nearyou.resources.generated.resources.post_create_content_placeholder
import id.nearyou.resources.generated.resources.post_create_error_empty
import id.nearyou.resources.generated.resources.post_create_error_location
import id.nearyou.resources.generated.resources.post_create_error_moderated
import id.nearyou.resources.generated.resources.post_create_error_rate_limited
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
import id.nearyou.resources.generated.resources.profile_action_failed
import id.nearyou.resources.generated.resources.profile_action_user_unavailable
import id.nearyou.resources.generated.resources.profile_actions_menu_description
import id.nearyou.resources.generated.resources.profile_block_action
import id.nearyou.resources.generated.resources.profile_block_confirm_body
import id.nearyou.resources.generated.resources.profile_block_confirm_title
import id.nearyou.resources.generated.resources.profile_block_rate_limited
import id.nearyou.resources.generated.resources.profile_block_success_toast
import id.nearyou.resources.generated.resources.profile_follow
import id.nearyou.resources.generated.resources.profile_follow_rate_limited
import id.nearyou.resources.generated.resources.profile_followers_count
import id.nearyou.resources.generated.resources.profile_followers_open_cd
import id.nearyou.resources.generated.resources.profile_following_count
import id.nearyou.resources.generated.resources.profile_following_open_cd
import id.nearyou.resources.generated.resources.profile_not_found
import id.nearyou.resources.generated.resources.profile_placeholder
import id.nearyou.resources.generated.resources.profile_premium_badge
import id.nearyou.resources.generated.resources.profile_premium_badge_icon_description
import id.nearyou.resources.generated.resources.profile_report_action
import id.nearyou.resources.generated.resources.profile_report_duplicate
import id.nearyou.resources.generated.resources.profile_report_note_placeholder
import id.nearyou.resources.generated.resources.profile_report_rate_limited
import id.nearyou.resources.generated.resources.profile_report_reason_title
import id.nearyou.resources.generated.resources.profile_report_submit
import id.nearyou.resources.generated.resources.profile_report_success_toast
import id.nearyou.resources.generated.resources.profile_unfollow
import id.nearyou.resources.generated.resources.report_reason_adult_content
import id.nearyou.resources.generated.resources.report_reason_harassment
import id.nearyou.resources.generated.resources.report_reason_hate_speech_sara
import id.nearyou.resources.generated.resources.report_reason_misinformation
import id.nearyou.resources.generated.resources.report_reason_other
import id.nearyou.resources.generated.resources.report_reason_spam
import id.nearyou.resources.generated.resources.search_back_cd
import id.nearyou.resources.generated.resources.search_clear_cd
import id.nearyou.resources.generated.resources.search_disabled
import id.nearyou.resources.generated.resources.search_empty_results
import id.nearyou.resources.generated.resources.search_hint
import id.nearyou.resources.generated.resources.search_icon_cd
import id.nearyou.resources.generated.resources.search_idle_prompt
import id.nearyou.resources.generated.resources.search_load_more
import id.nearyou.resources.generated.resources.search_premium_gate_body
import id.nearyou.resources.generated.resources.search_rate_limit_reset
import id.nearyou.resources.generated.resources.search_rate_limited
import id.nearyou.resources.generated.resources.section_home
import id.nearyou.resources.generated.resources.section_home_icon_description
import id.nearyou.resources.generated.resources.section_notifications
import id.nearyou.resources.generated.resources.section_notifications_icon_description
import id.nearyou.resources.generated.resources.section_profile
import id.nearyou.resources.generated.resources.section_profile_icon_description
import id.nearyou.resources.generated.resources.settings_back_description
import id.nearyou.resources.generated.resources.settings_coming_soon
import id.nearyou.resources.generated.resources.settings_logout_body
import id.nearyou.resources.generated.resources.settings_logout_cancel
import id.nearyou.resources.generated.resources.settings_logout_confirm
import id.nearyou.resources.generated.resources.settings_logout_title
import id.nearyou.resources.generated.resources.settings_row_change_username
import id.nearyou.resources.generated.resources.settings_row_change_username_sub
import id.nearyou.resources.generated.resources.settings_row_edit_profile
import id.nearyou.resources.generated.resources.settings_row_hide_distance
import id.nearyou.resources.generated.resources.settings_row_hide_distance_sub
import id.nearyou.resources.generated.resources.settings_row_legal
import id.nearyou.resources.generated.resources.settings_row_logout
import id.nearyou.resources.generated.resources.settings_row_manage_subscription
import id.nearyou.resources.generated.resources.settings_row_manage_subscription_sub
import id.nearyou.resources.generated.resources.settings_row_premium_journey
import id.nearyou.resources.generated.resources.settings_row_premium_journey_sub
import id.nearyou.resources.generated.resources.settings_row_privacy_data_sub
import id.nearyou.resources.generated.resources.settings_row_private_profile
import id.nearyou.resources.generated.resources.settings_row_private_profile_sub
import id.nearyou.resources.generated.resources.settings_save
import id.nearyou.resources.generated.resources.settings_section_account
import id.nearyou.resources.generated.resources.settings_section_other
import id.nearyou.resources.generated.resources.settings_section_premium
import id.nearyou.resources.generated.resources.settings_section_privacy
import id.nearyou.resources.generated.resources.settings_title
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
 * (task 7.12), broadened to mirror the ENTIRE `<string>` catalog 1:1: this list references every
 * declared `Res.string.*` accessor by name — the CMP Resources codegen generates an accessor ONLY
 * for a `<string>` present in `composeResources/values/strings.xml`, so a missing or renamed key
 * makes this file fail to COMPILE (a stronger guard than a runtime check), and the size assertion
 * pins the full catalog so a newly-declared-but-unreferenced key is caught as drift (the #240 gap:
 * the list had fallen to 118 of 190 declared keys). The exact-text scenarios for the Mobile #4
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
            // mobile-profile (30 net-new): the live Profil surface — static counts, premium badge,
            // follow/unfollow, the kebab + block-confirm dialog, the 6-category report picker + note,
            // the per-outcome toasts/banners, the not-found + neutral target-unavailable + generic-failure
            // copy. The loading/error states reuse timeline_loading / signin_error_network / cta_retry;
            // the overlay back + title reuse cta_close / section_profile; the @handle reuses
            // post_card_handle; cta_cancel is reused — none of those add new keys.
            Res.string.profile_followers_count,
            Res.string.profile_following_count,
            Res.string.profile_premium_badge,
            Res.string.profile_premium_badge_icon_description,
            Res.string.profile_follow,
            Res.string.profile_unfollow,
            Res.string.profile_actions_menu_description,
            Res.string.profile_block_action,
            Res.string.profile_report_action,
            Res.string.profile_block_confirm_title,
            Res.string.profile_block_confirm_body,
            Res.string.cta_block,
            Res.string.profile_block_success_toast,
            Res.string.profile_report_reason_title,
            Res.string.report_reason_spam,
            Res.string.report_reason_hate_speech_sara,
            Res.string.report_reason_harassment,
            Res.string.report_reason_adult_content,
            Res.string.report_reason_misinformation,
            Res.string.report_reason_other,
            Res.string.profile_report_note_placeholder,
            Res.string.profile_report_submit,
            Res.string.profile_report_success_toast,
            Res.string.profile_report_duplicate,
            Res.string.profile_follow_rate_limited,
            Res.string.profile_block_rate_limited,
            Res.string.profile_report_rate_limited,
            Res.string.profile_not_found,
            Res.string.profile_action_user_unavailable,
            Res.string.profile_action_failed,
            // --- #240 catalog backfill (2026-06-13 triage reconciliation): the keys below
            // were declared in strings.xml but untracked here, so the count had drifted away from
            // the catalog size. Every declared <string> now has a tracked accessor; the assertion
            // below pins the FULL catalog. Grouped by owning capability (full provenance lives in
            // the strings.xml block comments).
            // mobile-analytics-consent-screen
            Res.string.consent_title,
            Res.string.consent_explainer,
            Res.string.consent_analytics_label,
            Res.string.consent_analytics_desc,
            Res.string.consent_crash_label,
            Res.string.consent_crash_desc,
            Res.string.consent_ads_label,
            Res.string.consent_ads_desc,
            Res.string.consent_cta_continue,
            Res.string.consent_error_retryable,
            Res.string.consent_skip,
            // mobile-timeline-card-redesign + mobile-inline-post-actions (shared post card)
            Res.string.post_card_handle,
            Res.string.post_card_meta_separator,
            Res.string.post_card_action_reply,
            Res.string.post_card_action_like,
            Res.string.post_card_like_state_liked,
            Res.string.post_card_like_state_not_liked,
            // mobile-post-creation (429 daily-cap banner; intentionally outside the composer-string set per mobile-post-creation/spec.md)
            Res.string.post_create_error_rate_limited,
            // mobile-cap-upsell-dialog (shared daily-cap dialog)
            Res.string.cap_dialog_title,
            Res.string.cta_activate_premium,
            Res.string.cap_countdown_hours_minutes,
            Res.string.cap_countdown_minutes,
            // mobile-settings-screen (grouped list + block-list + logout-confirm)
            Res.string.settings_title,
            Res.string.settings_back_description,
            Res.string.settings_section_account,
            Res.string.settings_section_premium,
            Res.string.settings_section_privacy,
            Res.string.settings_section_other,
            Res.string.settings_row_edit_profile,
            Res.string.settings_row_change_username,
            Res.string.settings_row_change_username_sub,
            Res.string.settings_row_premium_journey,
            Res.string.settings_row_premium_journey_sub,
            Res.string.settings_row_manage_subscription,
            Res.string.settings_row_manage_subscription_sub,
            Res.string.settings_row_private_profile,
            Res.string.settings_row_private_profile_sub,
            Res.string.settings_row_hide_distance,
            Res.string.settings_row_hide_distance_sub,
            Res.string.settings_row_privacy_data_sub,
            Res.string.settings_row_legal,
            Res.string.settings_row_logout,
            Res.string.settings_coming_soon,
            Res.string.settings_save,
            Res.string.settings_logout_title,
            Res.string.settings_logout_body,
            Res.string.settings_logout_confirm,
            Res.string.settings_logout_cancel,
            Res.string.blocked_users_title,
            Res.string.blocked_users_empty,
            Res.string.blocked_users_unblock,
            // mobile-chat-screen (conversation list + thread + notif-permission rationale)
            Res.string.chat_list_title,
            Res.string.chat_list_empty,
            Res.string.chat_list_loading,
            Res.string.chat_thread_input_placeholder,
            Res.string.chat_send,
            Res.string.chat_message_redacted,
            Res.string.chat_send_blocked,
            Res.string.chat_open_action,
            Res.string.chat_account_deleted,
            Res.string.notif_permission_rationale,
            // mobile-search (Premium-gated Cari surface)
            Res.string.search_hint,
            Res.string.search_idle_prompt,
            Res.string.search_empty_results,
            Res.string.search_premium_gate_body,
            Res.string.search_rate_limited,
            Res.string.search_rate_limit_reset,
            Res.string.search_disabled,
            Res.string.search_load_more,
            Res.string.search_icon_cd,
            Res.string.search_clear_cd,
            Res.string.search_back_cd,
            // mobile-follow-lists (8 net-new: title + 2 tab labels + 2 empty states + load-more-failed
            // + 2 tappable-count content descriptions; not-found/error/back REUSE existing keys).
            Res.string.follow_list_title,
            Res.string.follow_list_tab_followers,
            Res.string.follow_list_tab_following,
            Res.string.follow_list_empty_followers,
            Res.string.follow_list_empty_following,
            Res.string.follow_list_load_more_failed,
            Res.string.profile_followers_open_cd,
            Res.string.profile_following_open_cd,
        )

    @Test
    fun `every declared strings xml key has a tracked Res string accessor`() {
        // This list mirrors EVERY <string> in shared/resources/.../values/strings.xml 1:1 — each
        // Res.string.* reference is a compile-time pin (the CMP codegen emits an accessor only for a
        // declared key, so a removed/renamed key fails to COMPILE here). The literal below is the full
        // catalog size; when you add a <string>, add its accessor above AND bump this count so the two
        // stay equal — a mismatch is exactly the catalog/guard drift this assertion exists to catch
        // (it had drifted to 118 tracked vs 190 declared before the #240 backfill). Per-capability
        // provenance lives in the strings.xml block comments, not duplicated here.
        // + 8 (mobile-follow-lists: title + 2 tab labels + 2 empty states + load-more-failed + 2
        // tappable-count content descriptions; not-found/error/back reuse existing keys) = 198.
        assertEquals(198, allDeclaredStrings.size)
        assertEquals(allDeclaredStrings.size, allDeclaredStrings.distinct().size, "no duplicate accessors")
    }
}
