## Context

`private_profile_opt_in` is the most "half-built" of the Premium privacy perks. Inventory of what already exists vs. what is missing:

| Layer | State | Where |
|---|---|---|
| Column `users.private_profile_opt_in BOOLEAN NOT NULL DEFAULT FALSE` | ✅ shipped | V2 (`docs/05` § Users Schema) |
| Column `users.privacy_flip_scheduled_at TIMESTAMPTZ` | ✅ shipped | V2 |
| Effective-private formula (`opt_in AND premium` OR 72h grace short-circuit) | ✅ shipped | `JdbcUserProfileReader.SQL_SELF` (`is_private`), `docs/05` § Effective private |
| Read enforcement (private Premium user's posts hidden from non-followers) | ✅ shipped | `premium-search` § "Premium private-profile gate hides posts from non-followers" |
| Downgrade writer (`opt_in → FALSE` on Premium lapse) | ✅ shipped | `PrivacyFlipWorker` (`@allow-privacy-write: worker`) |
| Webhook sets/clears `privacy_flip_scheduled_at` | ✅ shipped | `subscription-billing-webhook` |
| **User-facing opt-in writer (`opt_in → TRUE`)** | ❌ **missing** | — (this change) |
| **Mobile Settings "Profil privat" toggle** | ❌ deferred dead row | `mobile-settings` |

The privacy-flag-write lint allowlist (`@allow-privacy-write: worker|user_settings`) already names a `user_settings` writer with no implementation — this change IS that writer. The shipped `hide-distance` capability (`PATCH` / `GET /api/v1/user/hide-distance` + the "Sembunyikan jarak" Settings toggle) is the structural precedent; we mirror its route/repository/DTO shape, its "write-anytime + read-gated" posture, and its mobile toggle wiring.

## Goals / Non-Goals

**Goals**
- Ship `PATCH` / `GET /api/v1/user/private-profile` as the sanctioned `user_settings` privacy-flag writer, mirroring `hide-distance`.
- Make the "Profil privat" toggle correct during the 72h privacy-flip grace window (opt-out ⇒ immediate public, the "confirm switch public" path).
- Wire the mobile Settings "Profil privat" row to the new endpoints (Premium-gated), removing the dead control.

**Non-Goals**
- **No new read semantics.** Private-profile's effect is already defined and enforced (the `premium-search` follower-only gate). This change adds NO private-hiding to the Nearby/Following/Global timelines, the profile page, post-detail, or notifications. Those surfaces' current behavior is intentional and unchanged (declared out-of-scope per docs/12 §3).
- **No migration.** Both columns exist (V2).
- **No change to `PrivacyFlipWorker` or the RevenueCat webhook.** The grace-clear-on-opt-out lives entirely in the new write endpoint.
- **No admin oversight surface** (a separate follow-on, paralleling `admin-premium-username-oversight`).

## Decisions

**D1 — Mirror `hide-distance`'s "write-anytime + read-gated" posture, NOT a write-time Premium gate.** The `PATCH` succeeds for any tier; effectiveness is gated at READ (the canonical `opt_in AND premium` formula). Storing intent for a Free caller is harmless (it produces no effect) and exactly mirrors how a downgraded user holds a stale `opt_in = TRUE`. The mobile UX is the tier gate (Free sees the upsell and issues no write). Rationale: consistency with the shipped sibling, and it keeps the endpoint a thin single-column write with no per-request subscription read.

**D2 — The write IS on the privacy-flag-write allowlist (the one real difference from `hide-distance`).** `hide_distance_opt_in` is neither `username` nor `private_profile_opt_in`, so it needed no annotation. `private_profile_opt_in` IS the privacy flag, so the repository `UPDATE` carries `// @allow-privacy-write: user_settings` (the reserved-but-unused allowlist entry). This is the entire reason the column was lint-gated — to ensure only the worker and this Settings flow write it.

**D3 — Opt-out clears `privacy_flip_scheduled_at` ("confirm switch public").** The effective-private formula is `(opt_in AND premium) OR (scheduled_at > now())`. A user in the 72h grace window (downgraded, `opt_in` still TRUE, `scheduled_at` in the future) is effectively private via the grace short-circuit. If they toggle "Profil privat" OFF and we wrote only `opt_in = FALSE`, the grace term would keep them private — a visibly broken toggle. So an opt-out write sets `private_profile_opt_in = FALSE, privacy_flip_scheduled_at = NULL` in one statement. This realizes the documented "Tap untuk Premium ulang atau **confirm switch public**" action (`docs/03` § Downgrade flow privacy flip). An opt-IN write touches only `private_profile_opt_in` (a Premium caller has no `scheduled_at`; a Free caller shouldn't reach this via the gated UI). The single `UPDATE` is own-row (`WHERE id = :principal`), covered by the one `@allow-privacy-write: user_settings` annotation.

**D4 — `GET` returns the STORED opt-in + effective-Premium, not the composite `is_private`.** The toggle reflects the user's stored intent (`private_profile_opt_in`) plus whether they're effectively Premium (`subscription_status IN ('premium_active','premium_billing_retry')`, derived from the JWT principal with no extra read) so the screen can choose interactive-toggle vs Premium-upsell. This mirrors `GET /api/v1/user/hide-distance` exactly. The composite `is_private` (incl. the grace short-circuit) stays the self-read concern of `user-profile-read`; this endpoint is the settings-control seam, not the visibility oracle.

**D5 — Mobile data seam mirrors the established ApiClient → Repository → sealed-Outcome pattern (docs/11 §2.6), reusing the `hide-distance` Settings wiring shape.** A `PrivateProfileApiClient` (HTTP boundary on the shared `Auth { bearer }` `HttpClient` — no ad-hoc client, no reimplemented token attach/refresh), a `PrivateProfileRepository` (DTO→domain + sealed outcome), and the `SettingsViewModel` holding the toggle state as a `StateFlow` field (docs/11 §2.2) — NOT a second bespoke networking pattern (anti-patchwork). No new Pattern-Registry entry.

## Risks / Trade-offs

- **The toggle's effect is currently search-only.** A user enabling "Profil privat" sees their posts hidden from non-followers in search, but their profile page identity/counts and their timeline posts remain visible to non-followers (those surfaces never gated on private). This is the EXISTING, intentional semantics — not a regression — but it means the perk is narrower than an Instagram-style "private account." Mitigation: the spec explicitly scopes broader read-hiding out (docs/12 §3) so the limited effect is a declared decision, not a silent single-layer slice. A future change MAY broaden it.
- **Grace-window edge cases.** D3 handles opt-out-during-grace. Opt-in-during-grace is unreachable via the gated UI (the caller is Free) and harmless via the API (stored, read-gated). Re-subscribe after a manual public-switch leaves `opt_in = FALSE` (the user's choice), correctly public until they re-enable.
- **Concurrent worker vs. user write.** If the hourly `PrivacyFlipWorker` and a user opt-out race, both converge to `opt_in = FALSE` (idempotent); the worker's `WHERE` keys on the deadline, and a user-cleared `scheduled_at = NULL` removes the row from the worker's candidate set — no lost-update hazard on this single boolean.

## Migration Plan

None. Both `private_profile_opt_in` and `privacy_flip_scheduled_at` exist since V2. No Flyway migration, no Supabase-parity change. The change is additive (new route + repository + mobile wiring) and ships behind no flag (the mobile UX tier-gate is the only gate; no kill switch needed for a self-scoped settings write).

## Open Questions

- **Should opt-in be Premium-gated at the API (vs. write-anytime)?** D1 chooses write-anytime for consistency with `hide-distance`. If a reviewer prefers a hard `403 premium_required` at the API (matching `premium-username-customization`'s gate), that's a one-line change — surfaced for the review loop. Default: write-anytime + UX gate.
- **Should the "Profil privat" toggle be disabled (not just upsell) during an active grace window?** During grace the caller is Free, so it renders the Free/upsell affordance; tapping it can't opt-in. The "confirm switch public" opt-out is the meaningful action, but it's an opt-OUT on a Free caller — does the Free affordance allow the opt-out PATCH? Proposed: yes — a Free caller MAY opt OUT (it's a privacy-reducing, always-allowed action that realizes "confirm switch public"); only opt-IN is Premium-gated. Confirm in review.
