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
import id.nearyou.resources.generated.resources.cta_retry
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
import id.nearyou.resources.generated.resources.signin_error_banned
import id.nearyou.resources.generated.resources.signin_error_network
import id.nearyou.resources.generated.resources.signin_error_no_account
import id.nearyou.resources.generated.resources.signin_error_token_invalid
import id.nearyou.resources.generated.resources.signin_loading
import id.nearyou.resources.generated.resources.signin_screen_title
import id.nearyou.resources.generated.resources.signup_error_account_exists
import id.nearyou.resources.generated.resources.signup_loading
import id.nearyou.resources.generated.resources.timeline_empty_nearby
import id.nearyou.resources.generated.resources.timeline_limit_hard
import id.nearyou.resources.generated.resources.timeline_limit_soft
import id.nearyou.resources.generated.resources.timeline_loading
import id.nearyou.resources.generated.resources.timeline_nearby_title
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
            // mobile-location-permission-flow (consent rationale + denial fallback + settings CTA)
            Res.string.location_consent_title,
            Res.string.location_consent_body,
            Res.string.location_consent_allow,
            Res.string.nearby_location_denied,
            Res.string.location_open_settings,
        )

    @Test
    fun `all Mobile 2 3 4 and 5 plus location string keys are declared`() {
        // 10 (Mobile #2/#2.5) + 8 (Mobile #3) + 8 (Mobile #4) + 5 (Mobile #5 timeline)
        // + 5 (mobile-location-permission-flow) = 36.
        assertEquals(36, allDeclaredStrings.size)
        assertEquals(allDeclaredStrings.size, allDeclaredStrings.distinct().size, "no duplicate accessors")
    }
}
