## Why

Several canonical specs still describe shipped surfaces as deferred or absent, or omit what shipped. Agents and reviewers read `openspec/specs/` as the source of truth, so a stale "deferred" or "exactly N" claim either sends them to rebuild something that exists or makes them flag correct code as drift. Issue [#499](https://github.com/aditrioka/nearyou-id/issues/499) found these in the 2026-09-25 mobile + admin spec-completion review (`dev/audits/2026-09-25-mobile-admin-review/`, findings M1-C6, M4-C10, A1-C6, A2-C2/C3). Two comments on the issue and three later finds extend the list.

Every row below was re-verified against the code on `origin/main` (24014b17) before any spec edit. PRs #528 and #529 didn't touch any of these specs.

## What Changes

Spec-only: no code, no migration, no runtime change. Requirement deltas plus Purpose fixes, and one docs/03 amendment.

| Capability | Stale text | Shipped reality | Delta |
|---|---|---|---|
| `mobile-settings` | § "mobile-settings owns the SettingsRoute contract and push semantics" says the profile gear is deferred to #288 | Gear shipped in PR #312 (`AppEntryProvider.kt:154`, `ProfileScreen.kt:244`, `ProfileScreenTest`) | MODIFIED |
| `csam-detection` | § "Admin CSAM review surface and the report-filing read path are deferred" | `/admin/csam` + takedown / Kominfo / decrypt ship (`AdminCsamRoute.kt:75-160`, capability `admin-csam-detection-log`) | RENAMED + MODIFIED |
| `moderation-queue` | § "No reader endpoint in V9" | Admin readers: `GET /admin/reports` (`ReportQueueRepository`), `GET /admin/username-oversight` (`UsernameOversightRepository`) | RENAMED + MODIFIED |
| `admin-panel-scaffold` | § "Shared base layout…" lists exactly five nav items and forbids "Feature flags" | 16 items in 6 groups (`layout.peb:54-75`; `AdminPanelScaffoldAuthTest` asserts 16) | MODIFIED |
| `admin-feature-flags` | § "The panel renders the canonical flag catalog…" says "exactly" a list without `premium_image_upload_cap_override` | Editable integer `[1, 10000]` (`FeatureFlagCatalog.kt:53`, `FeatureFlagCatalogTest`) | MODIFIED (catalog + type-validation) |
| `mobile-post-detail` | § "By-id post fetch is deferred" says the screen issues no by-id GET | Resume-time freshness read `SinglePostApiClient.fetchPost` (`mobile-post-editing`) + deep-link `fetchFullPost` (`mobile-notifications-list`) ship | RENAMED + MODIFIED |
| `mobile-premium-username` | § "Proactive cooldown state, distinct unavailable messages, downgrade banner, and autocomplete are explicitly deferred" + § "Screen state mapping…" | Operator decision 2026-09-26: distinct 409 messages are **not planned** (anti-probing; #334 closed). Autocomplete isn't a username-screen feature (#336 → duplicate of #252, the Search autocomplete) | RENAMED + MODIFIED, and MODIFIED screen-state |
| `mobile-profile` | No requirement for the resume re-read (only `mobile-premium-username` implies it) | PR #521: `ProfileViewModel.refresh()` on `ON_RESUME`, with the rules covered by `ProfileViewModelTest` / `ProfileScreenTest` | ADDED |

**Purpose fixes (applied in the archive commit).** Delta files can't carry a `## Purpose`, so these are direct edits in the archive commit, as `logout-revocation` (PR #463) did when it fixed a stale Purpose:

- `mobile-notifications-list` says deep-link tap-through is deferred. It shipped. Actor-username rendering and live badge updates do stay deferred.
- `mobile-settings` lists "three backed actions" and says the gear is deferred.
- `csam-detection` needs no Purpose change.
- `moderation-queue` says "only the first writer ships now" and that the `resolved_by` FK is deferred. V16 backfilled the FK.
- `admin-panel-scaffold` still describes the unauthenticated scaffold (nav stub, footer, production mount guard).
- `admin-feature-flags` names a partial integer list and says the wordlist editor is "deferred".
- `mobile-post-detail` calls the notifications list "future" and says reply cards show "content + timestamp only".
- `mobile-premium-username` says all four fuller-UX items are "tracked as follow-ups".

**docs amendment.** `docs/03-UX-Design.md` § Customization Screen still links #334 as "distinct per-reason messages" tracking and #336 as "autocomplete". It changes to say the generic message is final (anti-probing), and the #336 link is removed, per the #336 triage plan. Without this, docs/03 would contradict the amended spec (CLAUDE.md reviewer rule 8).

**Issue bookkeeping (no closure in this PR).** #336 will be closed as a duplicate of #252 after this merges (operator decision 2026-09-26); this PR only records it. #333 and #335 stay open: they are deferrals (a) and (c) of the renamed `mobile-premium-username` requirement, which their eventual change will MODIFY under the NEW header.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `mobile-settings`: SettingsRoute ownership requirement records the shipped self-profile gear entry affordance.
- `csam-detection`: the "admin surface deferred" requirement becomes a delegation to the shipped `admin-csam-detection-log`.
- `moderation-queue`: the "no reader endpoint in V9" requirement becomes "read only by authenticated admin-panel surfaces".
- `admin-panel-scaffold`: base-layout sidebar lists the 16 shipped nav items in 6 groups.
- `admin-feature-flags`: catalog and type validation include `premium_image_upload_cap_override`.
- `mobile-post-detail`: the "by-id fetch deferred" requirement becomes "by-id reads never gate the header's first paint".
- `mobile-premium-username`: deferral requirement splits into deferred (a, c) and not-planned (b, d); the screen-state wording follows.
- `mobile-profile`: adds the resume re-read requirement.

## Impact

- **Code**: none. No Kotlin, SQL, template, or resource change; no new test.
- **Specs**: 8 capability deltas (above). Purpose fixes on 7 specs: the 6 deltas other than `csam-detection` and `mobile-profile`, plus `mobile-notifications-list`.
- **Docs**: `docs/03-UX-Design.md` § Customization Screen, one paragraph and one bullet.
- **Issues**: closes #499. Records #336 → duplicate-of-#252 (closed after merge, not by this PR).
- **Follow-on**: a future #333/#335 change MUST use the new `mobile-premium-username` header as its MODIFIED/RENAMED FROM.
