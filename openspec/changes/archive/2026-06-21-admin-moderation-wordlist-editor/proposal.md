## Why

The text-moderation pipeline reads two operator-curated wordlists — `moderation_profanity_list` (Layer 1 sync REJECT) and `moderation_uu_ite_list` (Layer 2 soft-flag) — from the Firebase Remote Config Server template, but there is no in-panel way to edit their *content*. The shipped Feature Flag Admin (`admin-feature-flags`, PR #297) deliberately renders both lists read-only (count + version) and carries a negative-guard requirement deferring content editing to this change; the interim is the Firebase Console (docs/07 § Moderation Runbook). For a solo operator, hand-editing 300+ entry JSON arrays in the Console — outside the audit trail, role gate, and rate limit that govern every other moderation action — is the remaining moderation-ops gap on the admin surface.

## What Changes

- **New per-list editor sub-surface** at `GET /admin/feature-flags/wordlists/{list}` (`{list}` ∈ `profanity` | `uu_ite`) — the frame-20 "edit" link target. Renders current entries (searchable for 300+ entries), the template version, the rate-limit quota chip, an add-single field, a bulk-CSV import, and per-entry removal, all **staged** into a previewed diff (added / removed / resulting count) before any publish. Read-only render: no publish, no audit row; any authenticated admin role may view.
- **New staged write** at `POST /admin/feature-flags/wordlists/{list}` — applies one editing session: serializes the new `List<String>` to a JSON-array string and publishes it to the Remote Config **Server** template via the shipped `RemoteConfigPublisher.publishServerParameter` (If-Match optimistic concurrency). Owner/admin role-gated, CSRF-protected, mandatory reason, value-validated, rate-limited on a **distinct** bucket, and recorded as **exactly one** immutable `admin_actions_log` row carrying a diff summary.
- **Empty-list guard**: a publish that would leave a list empty is rejected — an empty array is treated by the reader as loader failure (cascades to fallback + Sentry WARN), silently disabling that moderation layer.
- **Entry normalization** matching the `KeywordMatcher` reader contract: trim, lowercase (Indonesian locale, diacritics preserved), dedup; blank entries rejected; CSV import skips duplicates and `#`-comment lines with a report.
- **Graceful degradation**: when Remote Config write credentials are absent (`NoOpRemoteConfigPublisher`), the page renders read-only with disabled controls and a notice; any attempted write fails safely (no publish, no audit row, no `500`).
- **MODIFIED** `admin-feature-flags`: its "wordlist editing is out of scope / no mutation surface exists" requirement is re-pointed — the two lists now render read-only summaries **with an edit affordance linking to this editor**; the feature-flags POST write path still does not mutate list content inline.
- **No migration; no new module.** Reuses `:infra:remote-config`, `admin_actions_log`, the admin session/CSRF/role seam, and the rate-limiter pattern. Edits the Remote Config Server template **only** — the Tier-3 repo `*.default.txt` and Tier-4 Secret Manager fallbacks stay on the quarterly legal-review path.

## Capabilities

### New Capabilities
- `admin-moderation-wordlist-editor`: in-panel content editing for the two moderation wordlists — render + search, staged add/remove/bulk-CSV with diff preview, Server-template publish with optimistic concurrency, owner/admin + CSRF + reason + rate-limit gates, empty-list guard, entry normalization, graceful read-only degradation, and exactly-one diff-summary audit row per published session.

### Modified Capabilities
- `admin-feature-flags`: the "Wordlist array-content editing is out of scope and guarded read-only" requirement changes — the two lists render read-only summaries with an edit affordance linking to `admin-moderation-wordlist-editor`; the feature-flags surface itself still performs no inline list-content mutation.

## Impact

- **Code:** `:backend:ktor` `admin` package — new route (`AdminWordlistEditorRoute` or sibling of `AdminFeatureFlagsRoute`), a wordlist-editor service, a distinct rate limiter (the `FeatureFlagToggleRateLimiter` pattern, new `moderation_wordlist_edited` action bucket), Pebble templates (frame-20 edit link + frame-21-idiom editor sub-surface) + HTMX. Reuses `:infra:remote-config` `RemoteConfigPublisher` unchanged (a thin typed `List<String>` ↔ JSON-array helper may be added there — design's choice; all vendor types stay inside the module).
- **APIs:** two new admin routes; no public/mobile API change.
- **Data:** none — no schema migration; audit rows reuse `admin_actions_log`.
- **Dependencies:** none new.
- **Config / ops:** requires the existing `firebase-admin-sa` credential to have Remote Config **write** IAM on the bound project (already a precondition of `admin-feature-flags`; degrades read-only without it). Edits propagate to enforcement after the ≤5-min moderation-list Redis cache TTL.
- **Docs:** docs/07 § Moderation Runbook gains the in-panel edit path (the Firebase-Console path stays documented for the Tier-3/Tier-4 fallbacks); closes follow-up #305.
