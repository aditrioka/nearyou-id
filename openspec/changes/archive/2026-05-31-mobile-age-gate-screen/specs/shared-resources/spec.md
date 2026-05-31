## MODIFIED Requirements

### Requirement: Foundational Bahasa Indonesia string surface

The `:shared:resources` module SHALL provide a foundational set of Bahasa Indonesia UI strings in `shared/resources/src/commonMain/composeResources/values/strings.xml` (Compose Multiplatform Resources canonical layout — `values/` is the base locale, matching Android resource convention), accessible from commonMain via the Compose `stringResource(Res.string.<name>)` accessor. The string keys and text content of the **Mobile #2 / #2.5 and Mobile #3 foundational sets** SHALL be byte-identical to the shipped strings (this change does NOT rewrite earlier copy). The full set, with Mobile #4 additions, SHALL include at minimum:

**Mobile #2 / #2.5 foundational strings (preserved byte-identical):**
- `app_name`, `error_generic`, `cta_continue`, `cta_cancel`, `cta_retry`, `cta_close`, `loading`, `empty_state_generic`, `home_placeholder_title`, `home_placeholder_version`

**Mobile #3 sign-in flow strings (preserved byte-identical):**
- `cta_signin_google`: "Masuk dengan Google" (the user-facing primary CTA per [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Auth Flow)
- `signin_screen_title`: "Masuk ke NearYouID"
- `signin_error_no_account`: "Akun belum terdaftar. Daftar dulu lewat pembaruan aplikasi berikutnya." (Mobile #3 temporary copy; **as of Mobile #4 this string is no longer rendered on the `404` path** — the `mobile-auth-signin` `404` handler now navigates to `AgeGateScreen`. The string is retained in the catalog for now; full removal or repurpose-to-network-edge is an implementation-time decision per the `mobile-age-gate-screen` design Open Questions)
- `signin_error_banned`: "Akun kamu telah dinonaktifkan. Hubungi support jika ini keliru." (matching [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Suspension UX permanent-ban wording byte-identical)
- `signin_error_network`: "Tidak bisa terhubung. Periksa koneksi internet kamu." (REUSED by the Mobile #4 signup flow for its `5xx` / network / `503` retryable-error path — generic network copy, no new key)
- `signin_error_token_invalid`: "Sesi Google bermasalah. Coba lagi." (REUSED by the Mobile #4 signup flow for its terminal `invalid_id_token` path — generic Google-session copy, no new key)
- `signin_loading`: "Sedang masuk…"
- `account_separation_disclosure`: "Akun Google dan akun Apple terpisah. Satu identifier = satu akun NearYouID" (matching [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Auth Flow wording byte-identical)

**Mobile #4 age-gate / signup flow strings (new in this change):**
- `age_gate_title`: "Verifikasi usia kamu" (the `AgeGateScreen` title)
- `age_gate_explainer`: "NearYouID hanya untuk pengguna berusia 18 tahun ke atas. Masukkan tanggal lahir kamu untuk melanjutkan." (states the 18+ minimum clearly per the PP 17/2025 "clear minimum-age information" obligation; `docs/06-Security-Privacy.md` § Age Gate)
- `age_gate_dob_label`: "Tanggal lahir" (the date-of-birth field label)
- `age_gate_dob_picker_cta`: "Pilih tanggal lahir" (the affordance that opens the Material 3 DatePicker)
- `cta_create_account`: "Buat akun" (the primary create-account CTA)
- `age_gate_under18_blocked`: "Platform ini hanya tersedia untuk pengguna usia 18 tahun ke atas." (the generic `403 user_blocked` copy; **byte-identical** to the under-18 reject wording in [`docs/06-Security-Privacy.md`](../../../../docs/06-Security-Privacy.md) § Age Gate, [`docs/02-Product.md`](../../../../docs/02-Product.md) § Age Gate, and [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Age Gate Screen)
- `signup_error_account_exists`: "Akun sudah terdaftar. Silakan masuk." (the `409 user_exists` copy that routes the user to sign in)
- `signup_loading`: "Sedang membuat akun…" (the in-flight signup state)

Text content for all strings SHALL match the Bahasa Indonesia copy in [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) / [`docs/06-Security-Privacy.md`](../../../../docs/06-Security-Privacy.md) for any string that has a documented canonical wording.

#### Scenario: strings.xml is present at the expected CMP Resources path

- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/values/`
- **THEN** the directory contains a `strings.xml` file; the Moko-convention directory `shared/resources/src/commonMain/moko-resources/MR/base/` no longer exists OR contains no `strings.xml`

#### Scenario: All Mobile #2 + #3 + #4 strings are declared

- **WHEN** inspecting `shared/resources/src/commonMain/composeResources/values/strings.xml`
- **THEN** the file contains `<string>` entries for ALL of: `app_name`, `error_generic`, `cta_continue`, `cta_cancel`, `cta_retry`, `cta_close`, `loading`, `empty_state_generic`, `home_placeholder_title`, `home_placeholder_version`, `cta_signin_google`, `signin_screen_title`, `signin_error_no_account`, `signin_error_banned`, `signin_error_network`, `signin_error_token_invalid`, `signin_loading`, `account_separation_disclosure`, `age_gate_title`, `age_gate_explainer`, `age_gate_dob_label`, `age_gate_dob_picker_cta`, `cta_create_account`, `age_gate_under18_blocked`, `signup_error_account_exists`, `signup_loading`

#### Scenario: Mobile #2 strings remain byte-identical to shipped content

- **WHEN** reading the `<string name="error_generic">` value
- **THEN** the text is `"Ada yang salah. Coba lagi sebentar."` (matching Mobile #2's shipped content exactly — this change does NOT rewrite copy)

- **WHEN** reading the `<string name="cta_cancel">` value
- **THEN** the text is `"Batal"` (matching the user-facing label canonical in [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md))

#### Scenario: Mobile #3 sign-in strings carry the canonical Bahasa Indonesia copy

- **WHEN** reading the `<string name="cta_signin_google">` value
- **THEN** the text is exactly `"Masuk dengan Google"` (matching [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Auth Flow paragraph beginning `1. Android: "Masuk dengan Google"`)

- **WHEN** reading the `<string name="signin_error_banned">` value
- **THEN** the text is exactly `"Akun kamu telah dinonaktifkan. Hubungi support jika ini keliru."` (matching [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Suspension UX byte-identical)

- **WHEN** reading the `<string name="account_separation_disclosure">` value
- **THEN** the text is exactly `"Akun Google dan akun Apple terpisah. Satu identifier = satu akun NearYouID"` (matching [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Auth Flow paragraph beginning `**Account separation disclosure**` byte-identical)

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

#### Scenario: Mobile #4 age-gate strings carry the canonical Bahasa Indonesia copy

- **WHEN** reading the `<string name="age_gate_under18_blocked">` value
- **THEN** the text is exactly `"Platform ini hanya tersedia untuk pengguna usia 18 tahun ke atas."` (byte-identical to the under-18 reject copy in [`docs/06-Security-Privacy.md`](../../../../docs/06-Security-Privacy.md) § Age Gate, [`docs/02-Product.md`](../../../../docs/02-Product.md) § Age Gate, and [`docs/03-UX-Design.md`](../../../../docs/03-UX-Design.md) § Age Gate Screen)

- **WHEN** reading the `<string name="cta_create_account">` value
- **THEN** the text is exactly `"Buat akun"`

- **WHEN** reading the `<string name="signup_error_account_exists">` value
- **THEN** the text is exactly `"Akun sudah terdaftar. Silakan masuk."`

- **WHEN** reading the `<string name="age_gate_title">` value
- **THEN** the text is exactly `"Verifikasi usia kamu"`

#### Scenario: home_placeholder_version supports format substitution

- **WHEN** reading the `<string name="home_placeholder_version">` value
- **THEN** the text contains exactly one `%1$s` placeholder so the rendered version string can be supplied at composition time via `stringResource(Res.string.home_placeholder_version, "1.0")`
