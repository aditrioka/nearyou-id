## 1. Pre-implementation re-check

- [x] 1.1 Confirm no substrate change: this change adds NO entry to `gradle/libs.versions.toml` (reuses `:infra:remote-config`) — the pre-implementation library re-check is N/A; record that in the apply kickoff note.
- [x] 1.2 Re-read the reader contract in `openspec/specs/content-moderation-keyword-lists/spec.md` (value = JSON-array-of-strings; empty array = loader failure + Sentry WARN; matcher lowercases Locale("id") with diacritics preserved; distinct-keyword counting) and confirm the editor's normalization + empty-list guard match it.
- [x] 1.3 Confirm `RemoteConfigPublisher.publishServerParameter` / `fetchServerTemplate` are unchanged and accept arbitrary string values (so a JSON-array string publishes via the existing seam); confirm `NoOpRemoteConfigPublisher` is the unconfigured binding.

## 2. Remote Config string-list seam (`:infra:remote-config`)

- [x] 2.1 Add a thin typed helper to `:infra:remote-config` (e.g. `RemoteConfigPublisher.publishServerStringList(name, entries: List<String>, expectedEtag)` delegating to `publishServerParameter` with `Json.encodeToString`, and a `parseServerStringList(rawValue): List<String>?` mirroring the reader's JSON-array parse) so the JSON-array wire shape is single-sourced and no `kotlinx.serialization` array decision leaks into `:backend:ktor` (design D8). Keep all vendor types inside the module.
- [x] 2.2 Unit-test the helper: round-trips a `List<String>` to a JSON-array string and back; tolerates an absent/in-app-default parameter (null) from `fetchServerTemplate`.

## 3. Wordlist editor service (`:backend:ktor` admin)

- [x] 3.1 Add a `WordlistTarget` enum/sealed type mapping `profanity` → `moderation_profanity_list` and `uu_ite` → `moderation_uu_ite_list`; reject unknown slugs.
- [x] 3.2 Implement entry normalization (design D5): trim, lowercase `Locale("id")` (diacritics preserved), dedup, drop blank entries; reject per-entry > 100 chars; reject resulting list > 10000 entries.
- [x] 3.3 Implement the diff computation: given the current normalized list + a staged resulting list, produce added/removed/resulting-count + a bounded changed-entries sample (design D3, Q1 sample size).
- [x] 3.4 Implement the empty-list guard (design D4): a resulting list with zero entries is a validation error (no publish).
- [x] 3.5 Implement CSV/bulk import parsing (design D5): split on newlines/commas, normalize, skip blank lines + leading-`#` comment lines + already-present entries, return an import report (added / duplicates skipped / comment+blank skipped).
- [x] 3.6 Implement the publish orchestration: fetch template (etag + current list) → normalize + diff → validate (reason non-blank, not no-op, not empty, within caps) → `publishServerStringList(..., expectedEtag)` → map `PublishResult` (Published → audit; StaleVersion → stale-retry; WriteUnavailable → fail-safe read-only; Failed → safe error) → on success write exactly one `moderation_wordlist_edited` audit row (diff summary in before/after state) in the same connection as the rate-limit COUNT.

## 4. Distinct rate limiter

- [x] 4.1 Add a `WordlistEditRateLimiter` (the `FeatureFlagToggleRateLimiter` pattern): trailing-hour `COUNT` of `moderation_wordlist_edited` rows for the admin on a caller-supplied `Connection`, cap 10/hour (design D2), distinct from the 5/hour `feature_flag_toggled` and 20/hour destructive buckets; the COUNT + success-INSERT share one connection.

## 5. Routes

- [x] 5.1 Add `GET /admin/feature-flags/wordlists/{list}` behind the admin session middleware: validate `{list}`, fetch current list + version + etag, render the editor (read-only render writes nothing); any authenticated role may view; unknown `{list}` → 404; write controls disabled for `moderator` and when write-unconfigured.
- [x] 5.2 Add `POST /admin/feature-flags/wordlists/{list}`: CSRF check first (`admin_csrf_violation` on mismatch) → owner/admin role-gate → rate-limit → service publish orchestration (CSRF-before-role matches the `admin-reserved-usernames-editor` precedent, so a token-less write is logged regardless of role); HTMX render + plain-`GET` fallback; surface the import report, the diff, stale-version retry, and the empty-list/validation errors inline.
- [x] 5.3 Mount the routes alongside `AdminFeatureFlagsRoute` (route stays thin; logic in the service).

## 6. Templates (Pebble + HTMX, docs/11 § 3.6)

- [x] 6.1 Update the frame-20 feature-flags template: render each wordlist as a read-only summary (count + version) with an "edit" affordance linking to `/admin/feature-flags/wordlists/{list}` (the `admin-feature-flags` MODIFIED requirement); keep `moderation_match_threshold` inline-editable.
- [x] 6.2 Add the editor sub-surface template in the frame-21 Reserved Usernames Editor idiom: searchable entry list, add-single, bulk-CSV import, per-entry remove (staged), diff preview (added/removed/resulting), ops-quota chip, reason field, audit/propagation banner (≤5-min cache TTL + repo-fallback-updated-separately note). HTML-escape all entries.
- [x] 6.3 If any `admin/static/*` asset changes (it should not), re-pin `htmx.min.js.SHA256SUMS` (CI lint integrity check); otherwise confirm no static asset changed.

## 7. Tests (`:backend:ktor`, `@Tags("database")` route tests; service unit tests)

- [x] 7.1 Service: normalization (lowercase id-locale + diacritic preserved, dedup, blank dropped, over-length rejected, over-cap rejected — the over-cap test MAY use a lowered injected cap rather than constructing 10001 entries); case+diacritic-collision dedup ("Anjïng"+"anjïng" → one); add-single treats a leading-`#` value as a literal keyword (the `#`-strip is CSV-import-only); diff counts (incl. a staged removal of an absent entry not incrementing removed); empty-list guard; CSV import report (duplicates + `#`/blank skipped, incl. the all-skipped → "0 added" benign case).
- [x] 7.2 Route GET: authenticated render shows current entries + version; read-only render writes nothing; unauthenticated → redirect; unknown `{list}` → 404; moderator GET renders read-only (no write controls).
- [x] 7.3 Route POST happy path: owner/admin valid publish → publishes + exactly one `moderation_wordlist_edited` audit row with diff summary; no-op rejected (no publish/no audit) — including the post-normalization no-op where a staged edit that *looks* changed normalizes back to the current list; blank reason rejected.
- [x] 7.4 Route POST guards: moderator write → 403 (no publish/no audit); CSRF missing → 403 (no publish); CSRF mismatch → 403 + `admin_csrf_violation`; empty-list publish rejected (no publish/no audit); stale-etag → StaleVersion rejected (no overwrite/no audit); write-unconfigured (NoOp publisher) → read-only render + write fails safely (no publish/no audit/no 500); rendered entries HTML-escaped (metacharacter entry).
- [x] 7.5 Rate limit: 11th write in trailing hour rejected (no publish/no audit); 10th succeeds; wordlist bucket independent of the 5/hour feature-flag-toggle bucket (exhausting one does not consume the other).
- [x] 7.6 `admin-feature-flags` surface regression: the feature-flags page renders the two lists with an edit affordance and `moderation_match_threshold` inline-editable; the feature-flags single-flag write path has no inline list-content mutation.
- [x] 7.7 Scope boundary ("mutates only the Server template"): a publish invokes the Server-template publish seam for the named parameter only (verify via the `RemoteConfigPublisher` mock-spy — exactly one `publishServerStringList` for the target `{list}` param) AND performs no write to the repo `*.default.txt` fallback files nor the `content-moderation-fallback-list` Secret Manager slot.

## 8. Spec + docs sync

- [x] 8.1 Confirm `openspec validate admin-moderation-wordlist-editor --strict` passes (NEW capability + the `admin-feature-flags` RENAMED+MODIFIED delta).
- [x] 8.2 Update `docs/07-Operations.md` § Core Features "Feature Flag Admin" + § Moderation Runbook: add the in-panel edit path (mark the array-content editor SHIPPED), keep the Firebase-Console path documented for the Tier-3/Tier-4 fallbacks.
- [x] 8.3 Reference follow-up #305 for closure at squash-merge (the editor it tracks ships here).

## 9. Verification gates (pre-push)

- [x] 9.1 `./gradlew ktlintCheck detekt :lint:detekt-rules:test` green (both lint frameworks; vendor-sdk-leakage-scan stays clean — no `com.google.*`/Firebase import in `:backend:ktor`).
- [x] 9.2 `./gradlew :backend:ktor:test` green; verify touched-area tests explicitly: `:backend:ktor:test --tests "*admin*wordlist*"` (DB tests included via the PG service container; CI runs `kotest.tags '!network'`).
- [x] 9.3 Manual verify-loop (admin panel boot): load the editor, stage an add + a remove, preview the diff, publish with a reason, confirm the audit row + the read-only-when-unconfigured degradation (KTOR_ENV/admin bootstrap per the verify-loop skill).
