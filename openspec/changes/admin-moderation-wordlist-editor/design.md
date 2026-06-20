## Context

The text-moderation pipeline (`content-moderation-keyword-lists`) reads two operator-curated wordlists from the Firebase Remote Config **Server** template: `moderation_profanity_list` (Layer 1, sync REJECT) and `moderation_uu_ite_list` (Layer 2, soft-flag → `moderation_queue`). Both are JSON-array-of-strings parameters, cached in Redis for 5 minutes (`{scope:mod_list}:{tier:profanity}` / `{tier:uu_ite}`), with a 4-tier fallback ladder (Redis → Remote Config → repo `*.default.txt` → Secret Manager).

The shipped Feature Flag Admin (`admin-feature-flags`, PR #297) renders both lists **read-only** (count + version) behind an explicit negative-guard requirement — "Wordlist array-content editing is out of scope and guarded read-only" — that names this change as the deferred owner. The interim for content edits is the Firebase Console (docs/07 § Moderation Runbook). The write seam already exists: `:infra:remote-config`'s `RemoteConfigPublisher.publishServerParameter(name, rawValue, expectedEtag)` patches a single parameter's `defaultValue.value` (a raw string) and PUTs the template with `If-Match`, returning a typed `PublishResult` (`Published` / `StaleVersion` / `WriteUnavailable` / `Failed`); `fetchServerTemplate(names)` returns the etag, version, and each parameter's raw value. Because a wordlist is just a string-valued parameter whose content is a JSON array, **no new publisher method and no schema migration are needed**.

This change is the per-list editor sub-surface (mockup frame 20's "edit" link), built in the frame-21 Reserved Usernames Editor idiom.

## Goals / Non-Goals

**Goals:**
- Let owner/admin curate the two wordlists in-panel — search, add single, bulk-CSV import, per-entry remove — staged into a single previewed diff and published once per session.
- Keep every list edit inside the same audit / role / CSRF / rate-limit governance that covers all other admin moderation actions.
- Reuse the existing `RemoteConfigPublisher` seam, `admin_actions_log`, and admin auth seam — no new module, no migration.
- Preserve the moderation reader's contract: never publish an empty list; normalize entries to match `KeywordMatcher`.

**Non-Goals:**
- Editing `moderation_match_threshold` (already editable on the feature-flags surface) or any non-wordlist flag.
- Touching the Tier-3 repo `*.default.txt` fallback or the Tier-4 `content-moderation-fallback-list` Secret Manager slot — those stay on the quarterly UU-ITE legal-review path (docs/07 line 214).
- Real-time enforcement: edits propagate after the ≤5-min moderation-list Redis TTL; no cache-bust is added in this change.
- A `KeywordMatcher` "test a sample sentence against the staged list" preview (a nice-to-have; deferred — see Open Questions).

## Standards conformance (docs/11 § 4 Pattern Registry)

- **Backend layering (docs/11 § 3.1):** route (`AdminWordlistEditorRoute`, thin) → service (`WordlistEditorService`, validation + diff + publish orchestration) → `:infra:remote-config` (vendor-fenced publish). No `com.google.*` / Firebase import enters `:backend:ktor` (the `vendor-sdk-leakage-scan` invariant); all REST/credential types stay inside `:infra:remote-config`.
- **Admin write seam:** reuses the established owner/admin role gate + `__Host-admin_session` + CSRF (`X-CSRF-Token` vs `csrf_token_hash`, `admin_csrf_violation` on mismatch) + immutable `admin_actions_log` (no UPDATE/DELETE at `admin_app`) — the same shape as `admin-feature-flags`, `admin-reserved-usernames-editor`, and the report-resolution actions.
- **Rate-limit-as-ledger (docs/05 + precedent):** the `FeatureFlagToggleRateLimiter` / `DestructiveActionRateLimiter` pattern — trailing-hour `COUNT` over `admin_actions_log` + the success-path `INSERT` on a single caller-supplied `Connection` so the count cannot drift from the ledger it gates.
- **Admin UI (docs/11 § 3.6):** Pebble + HTMX + vendored admin CSS; HTML-escape all rendered values; plain-`GET` fallback alongside the HTMX render (the established admin-panel pattern).
- **No new pattern introduced** for any Pattern-Registry concern → no docs/11 § Pattern Registry amendment required by this change.

## Decisions

### D1 — Staged-diff-publish (one publish + one audit row per session), not per-entry-immediate
The editor stages adds/removes client-visible, renders a diff (added / removed / resulting count), and on submit publishes the whole resulting list once. **Why:** lists are 300+ entries and every `publishServerParameter` call republishes the entire JSON array anyway; per-entry-immediate (the reserved-usernames model) would burn the rate budget, spray the audit log, and can't satisfy the issue's "diff" requirement. **Alternative considered:** per-entry-immediate publish — rejected for scale, rate budget, and the diff requirement. The reserved-usernames editor stays the UI *idiom* (list + add single + bulk CSV + per-entry remove); the *publish model* differs (batch, not per-row).

### D2 — Distinct action type `moderation_wordlist_edited` + distinct rate-limit bucket
Writes audit as `action_type = 'moderation_wordlist_edited'` and are capped on their own trailing-hour bucket, **separate** from the 5/hour `feature_flag_toggled` bucket. **Why:** matches the established distinct-action-distinct-bucket precedent (`rejected_identifier_cleared` 10/hr; `subscription_grace_expedite` distinct) and stops wordlist sessions from consuming the flag-toggle budget (and vice versa). **Cap: 10 published sessions / admin / trailing hour** — publishes are once-per-session and curation is bursty (a batch of adds after a legal review), so 10 is comfortable headroom while still bounding a compromised session; aligns numerically with the other restorative/config buckets. The COUNT + success-INSERT run on one `Connection`.

### D3 — Audit row stores a diff summary, not the full arrays
`before_state` / `after_state` carry `{list, beforeCount, afterCount, added: <int>, removed: <int>, beforeVersion, afterVersion}` (counts + a bounded sample of changed entries, not the full 300-entry arrays). **Why:** full arrays bloat `admin_actions_log` and are already recoverable from Remote Config version history (the `afterVersion` pointer makes the row a precise index into that history). A bounded sample (e.g. first N added/removed) keeps the row human-useful without unbounded growth. **Alternative:** full before/after arrays — rejected for row bloat; the version pointer is the system of record for exact content.

### D4 — Empty-list guard (load-bearing safety)
A publish whose resulting list is empty is rejected inline (validation error, no publish, no audit row). **Why:** `content-moderation-keyword-lists` treats an empty Remote Config array as **loader failure** — it cascades to the repo/Secret-Manager fallback and emits a Sentry WARN (`reason = "empty"`). An operator who deletes every entry expecting "no moderation" would instead silently fall back to the repo default list AND trip an alert; worse, a near-empty publish could disable Layer 1 profanity enforcement. The editor refuses to ever produce an empty list.

### D5 — Entry normalization aligned to the `KeywordMatcher` reader
Each entry is trimmed, lowercased with `Locale("id")` (Indonesian-locale lowercasing, diacritics **preserved** — the matcher's documented contract), and the resulting list is de-duplicated (the matcher counts distinct keywords, so duplicates are inert noise). Blank / whitespace-only entries are rejected. Sanity caps: **per-entry ≤ 100 chars**, **list ≤ 10000 entries** (well above realistic curation; bounds a paste accident and matches the threshold's `[1,10000]` upper bound for consistency). **Why:** storing entries in the exact form the matcher compares against makes the displayed list match enforcement reality and makes dedup meaningful. On **CSV / bulk import**, leading-`#` lines (Tier-3 repo-file comment syntax, NOT Remote Config array entries) and blank lines are skipped, and entries already present are skipped — both reported back ("N added, M duplicates skipped, K comment/blank lines skipped"), the `admin-reserved-usernames-editor` "skipped with a report" precedent.

### D6 — Optimistic concurrency via the existing If-Match seam
On render, fetch the Server-template etag + current list; the publish carries that etag. `RemoteConfigPublisher` re-reads at publish time and returns `StaleVersion` if the template advanced (a concurrent flag toggle or another wordlist edit) — the editor surfaces a "template changed, reload and re-apply" prompt, performs no overwrite, and writes no audit row. **Why:** the wordlist editor and the flag-toggle surface write the *same* Server template; without If-Match a wordlist publish could clobber a concurrent `attestation_mode` change. This is exactly the `admin-feature-flags` stale-version contract, reused verbatim.

### D7 — Graceful read-only degradation when write-unconfigured
When `RemoteConfigPublisher.isConfigured()` is false (`NoOpRemoteConfigPublisher` — no `firebase-admin-sa` write credential), the editor renders the current list read-only with disabled controls and an inline notice; any submitted write fails safely (no publish, no audit row, no `500`). **Why:** mirrors the `admin-feature-flags` read-only-when-unconfigured requirement so a deploy missing the credential still boots and the panel degrades instead of crashing. (Read of the current list still works via `fetchServerTemplate`; only the write path is gated.)

### D8 — Serialize in `:infra:remote-config`, keep the JSON shape canonical
The `List<String>` ↔ JSON-array-string conversion lives behind a thin typed helper in `:infra:remote-config` (e.g. `publishServerStringList(name, entries, etag)` delegating to `publishServerParameter` with `Json.encodeToString`), and a matching parse on read. **Why:** the reader (`ModerationListLoader`) deserializes the same JSON-array shape; colocating serialize+parse in the module that owns the Remote Config contract keeps the wire shape in one place and keeps `kotlinx.serialization` array-encoding decisions out of the admin service. The admin service sees only `List<String>` and `PublishResult`. (Lightweight enough to inline in the service instead — but the helper keeps the contract single-sourced.)

### D9 — Route placement under the feature-flags path
Routes mount at `GET|POST /admin/feature-flags/wordlists/{list}` (sub-paths of the feature-flags surface), reached from the frame-20 "edit" link. **Why:** the lists live on the Feature Flag Admin page conceptually and in the mockup; nesting keeps the breadcrumb/nav coherent (`Konfigurasi › Feature Flags › <list>`) and signals the shared Server-template backing. **Alternative:** a top-level `/admin/moderation-wordlists` — rejected; it would imply a separate nav entry the mockup doesn't have and divorce the editor from its parent surface.

## Risks / Trade-offs

- **Risk: an operator empties a list expecting "moderation off."** → D4 hard-rejects empty publishes with an explanatory message pointing at the feature-flag kill switches (`perspective_api_enabled`, etc.) for actually disabling a layer.
- **Risk: a giant CSV paste (tens of thousands of lines) bloats the template / request.** → D5 caps list size at 10000 and per-entry length at 100; over-cap import is rejected inline with a report, before any publish.
- **Risk: concurrent edits clobber each other (wordlist vs flag toggle on the same template).** → D6 If-Match returns `StaleVersion`; no overwrite, retry prompt.
- **Risk: edit appears to "not take effect."** → ≤5-min Redis cache TTL is surfaced in the UI notice (docs/07 runbook step 4); enforcement reflects the new list after the cache expires.
- **Trade-off: diff-summary audit (D3) doesn't store exact arrays.** → Mitigated by the `afterVersion` pointer into Remote Config version history; the diff summary plus the version is sufficient for "who changed what, when, and by how much," and the Console retains full version history for forensic recall.
- **Risk: the Tier-3 repo fallback drifts from the live list over time** (editor only touches Remote Config). → Out of scope by design (Non-Goals); the UI notes the repo fallback is updated separately at quarterly legal review (docs/07 line 214), so the divergence is expected and documented, not silent.
- **Trade-off: editing HTML-rendered operator content is an XSS surface.** → All entries HTML-escaped on render (the feature-flags precedent); a scenario asserts metacharacter entries render escaped.

## Migration Plan

No schema migration, no data backfill, no new secret slot. Deploy is additive: two new admin routes + templates + a sibling rate limiter. Rollback is a straight revert (the lists remain editable via the Firebase Console interim throughout). The `admin-feature-flags` spec's read-only negative-guard requirement is re-pointed (RENAMED + MODIFIED) so the "edit" link is now legitimate; no behavior of the existing feature-flags POST path changes. Precondition for the write path to function in an environment: the `firebase-admin-sa` credential holds Remote Config write IAM (already the `admin-feature-flags` precondition) — absent it, the editor degrades read-only (D7).

## Open Questions

- **Q1 — sample size in the diff-summary audit (D3):** how many added/removed entries to retain inline (suggest first 20 of each, with `+N more`)? Settle at implementation; does not affect the spec contract (the requirement is "diff summary, not full arrays").
- **Q2 — "test a sentence" preview:** deferred (Non-Goal). Worth a follow-up if operators want to dry-run the staged list against `KeywordMatcher` before publishing. Not in this change's scope.
