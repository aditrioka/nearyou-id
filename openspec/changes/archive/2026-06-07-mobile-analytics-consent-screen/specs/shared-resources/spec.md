## ADDED Requirements

### Requirement: Analytics-consent Bahasa Indonesia strings

The `:shared:resources` module SHALL declare the Bahasa Indonesia UI strings for the analytics-consent onboarding screen in `shared/resources/src/commonMain/composeResources/values/strings.xml`, accessible via `stringResource(Res.string.<name>)`. No earlier (Mobile #2–#7) string SHALL be altered. The three per-category **description** strings SHALL be **byte-identical** to the data-summary copy in [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § "Analytics & Tracking Consent Screen (UU PDP)" (the only consent strings the doc pins verbatim); the title, explainer, labels, CTA, error, and skip strings are new wording consistent with the doc's intent. The set SHALL include:

- `consent_title`: "Privasi & data" (the `ConsentScreen` title)
- `consent_explainer`: "Pilih data yang boleh kami kumpulkan untuk meningkatkan NearYouID. Kamu bisa mengubahnya kapan saja di Pengaturan." (states that the choice is changeable later, per the doc's "Settings page allows the user to change the toggle")
- `consent_analytics_label`: "Analitik penggunaan" (the Analytics toggle label)
- `consent_analytics_desc`: "Bantu kami perbaiki aplikasi dengan data penggunaan anonim (Amplitude)" (**byte-identical** to `docs/03-UX-Design.md`)
- `consent_crash_label`: "Laporan crash" (the Crash Reporting toggle label)
- `consent_crash_desc`: "Laporkan crash otomatis untuk perbaikan bug (Sentry)" (**byte-identical** to `docs/03-UX-Design.md`)
- `consent_ads_label`: "Personalisasi iklan" (the Ads Personalization toggle label)
- `consent_ads_desc`: "Iklan dapat disesuaikan dengan minat kamu (Google AdMob UMP)" (**byte-identical** to `docs/03-UX-Design.md`)
- `consent_cta_continue`: "Simpan & lanjutkan" (the primary continue CTA — names the persistence action)
- `consent_error_retryable`: "Gagal menyimpan preferensi. Coba lagi." (the retryable submit-error copy)
- `consent_skip`: "Lewati untuk sekarang" (the proceed-anyway affordance shown only after a failed submit, per the `mobile-analytics-consent` non-trapping requirement)

#### Scenario: All analytics-consent strings are declared at the CMP Resources path

- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/values/strings.xml`
- **THEN** the file contains `<string>` entries for ALL of: `consent_title`, `consent_explainer`, `consent_analytics_label`, `consent_analytics_desc`, `consent_crash_label`, `consent_crash_desc`, `consent_ads_label`, `consent_ads_desc`, `consent_cta_continue`, `consent_error_retryable`, `consent_skip`

#### Scenario: The three category descriptions are byte-identical to docs/03-UX-Design.md

- **WHEN** comparing `consent_analytics_desc`, `consent_crash_desc`, and `consent_ads_desc` against the data-summary bullets in `docs/03-UX-Design.md` § "Analytics & Tracking Consent Screen (UU PDP)"
- **THEN** each string's text is byte-identical to its corresponding documented bullet ("Bantu kami perbaiki aplikasi dengan data penggunaan anonim (Amplitude)", "Laporkan crash otomatis untuk perbaikan bug (Sentry)", "Iklan dapat disesuaikan dengan minat kamu (Google AdMob UMP)")

#### Scenario: No earlier string is altered

- **WHEN** diffing `strings.xml` against its pre-change state
- **THEN** the only changes are additions of the `consent_*` keys above; no existing `<string>` entry's name or text is modified or removed
