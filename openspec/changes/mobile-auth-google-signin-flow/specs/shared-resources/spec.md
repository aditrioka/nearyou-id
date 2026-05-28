## MODIFIED Requirements

### Requirement: Foundational Bahasa Indonesia string surface

The `:shared:resources` module SHALL provide a foundational set of Bahasa Indonesia UI strings in `shared/resources/src/commonMain/composeResources/values/strings.xml` (Compose Multiplatform Resources canonical layout — `values/` is the base locale, matching Android resource convention), accessible from commonMain via the Compose `stringResource(Res.string.<name>)` accessor. The string keys and text content of the **Mobile #2 / #2.5 foundational set** SHALL be byte-identical to the shipped strings (this change does NOT rewrite Mobile #2's copy). The full set, with Mobile #3 additions, SHALL include at minimum:

**Mobile #2 / #2.5 foundational strings (preserved byte-identical):**
- `app_name`, `error_generic`, `cta_continue`, `cta_cancel`, `cta_retry`, `cta_close`, `loading`, `empty_state_generic`, `home_placeholder_title`, `home_placeholder_version`

**Mobile #3 sign-in flow strings (new in this change):**
- `cta_signin_google`: "Masuk dengan Google" (the user-facing primary CTA per [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Auth Flow)
- `signin_screen_title`: "Masuk ke NearYouID"
- `signin_error_no_account`: "Akun belum terdaftar. Daftar dulu lewat pembaruan aplikasi berikutnya." (temporary copy per the `mobile-auth-google-signin-flow` change Decision 7; Mobile #4 replaces this branch with age-gate-then-signup navigation, retiring this string OR repurposing it for the network-edge-case-only path)
- `signin_error_banned`: "Akun kamu telah dinonaktifkan. Hubungi support jika ini keliru." (matching [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Suspension UX wording byte-identical)
- `signin_error_network`: "Tidak bisa terhubung. Periksa koneksi internet kamu."
- `signin_error_token_invalid`: "Sesi Google bermasalah. Coba lagi."
- `signin_loading`: "Sedang masuk…"
- `account_separation_disclosure`: "Akun Google dan akun Apple terpisah. Satu identifier = satu akun NearYouID" (matching [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Auth Flow wording byte-identical)

Text content for all Mobile #3 strings SHALL match the Bahasa Indonesia copy in [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) for any string that has a documented canonical wording.

#### Scenario: strings.xml is present at the expected CMP Resources path

- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/values/`
- **THEN** the directory contains a `strings.xml` file; the Moko-convention directory `shared/resources/src/commonMain/moko-resources/MR/base/` no longer exists OR contains no `strings.xml`

#### Scenario: All Mobile #2 + #3 strings are declared

- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/values/strings.xml`
- **THEN** the file contains `<string>` entries for ALL of: `app_name`, `error_generic`, `cta_continue`, `cta_cancel`, `cta_retry`, `cta_close`, `loading`, `empty_state_generic`, `home_placeholder_title`, `home_placeholder_version`, `cta_signin_google`, `signin_screen_title`, `signin_error_no_account`, `signin_error_banned`, `signin_error_network`, `signin_error_token_invalid`, `signin_loading`, `account_separation_disclosure`

#### Scenario: Mobile #2 strings remain byte-identical to shipped content

- **WHEN** reading the `<string name="error_generic">` value
- **THEN** the text is `"Ada yang salah. Coba lagi sebentar."` (matching Mobile #2's shipped content exactly — this change does NOT rewrite copy)

- **WHEN** reading the `<string name="cta_cancel">` value
- **THEN** the text is `"Batal"` (matching the user-facing label canonical in [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md))

#### Scenario: Mobile #3 sign-in strings carry the canonical Bahasa Indonesia copy

- **WHEN** reading the `<string name="cta_signin_google">` value
- **THEN** the text is exactly `"Masuk dengan Google"` (matching [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Auth Flow line 36)

- **WHEN** reading the `<string name="signin_error_banned">` value
- **THEN** the text is exactly `"Akun kamu telah dinonaktifkan. Hubungi support jika ini keliru."` (matching [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Suspension UX byte-identical)

- **WHEN** reading the `<string name="account_separation_disclosure">` value
- **THEN** the text is exactly `"Akun Google dan akun Apple terpisah. Satu identifier = satu akun NearYouID"` (matching [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Auth Flow line 43 byte-identical)

- **WHEN** reading the `<string name="signin_error_network">` value
- **THEN** the text is exactly `"Tidak bisa terhubung. Periksa koneksi internet kamu."`

- **WHEN** reading the `<string name="signin_error_token_invalid">` value
- **THEN** the text is exactly `"Sesi Google bermasalah. Coba lagi."`

- **WHEN** reading the `<string name="signin_error_no_account">` value
- **THEN** the text is exactly `"Akun belum terdaftar. Daftar dulu lewat pembaruan aplikasi berikutnya."` (per the `mobile-auth-google-signin-flow` Decision 7 temporary copy)

- **WHEN** reading the `<string name="signin_screen_title">` value
- **THEN** the text is exactly `"Masuk ke NearYouID"`

- **WHEN** reading the `<string name="signin_loading">` value
- **THEN** the text is exactly `"Sedang masuk…"`

#### Scenario: home_placeholder_version supports format substitution

- **WHEN** reading the `<string name="home_placeholder_version">` value
- **THEN** the text contains exactly one `%1$s` placeholder so the rendered version string can be supplied at composition time via `stringResource(Res.string.home_placeholder_version, "1.0")`
