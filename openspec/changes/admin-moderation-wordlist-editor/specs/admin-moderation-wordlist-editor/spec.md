## ADDED Requirements

### Requirement: Authenticated GET /admin/feature-flags/wordlists/{list} renders the wordlist editor

The admin panel SHALL expose `GET /admin/feature-flags/wordlists/{list}` behind the standard admin session middleware, where `{list}` is `profanity` (→ `moderation_profanity_list`) or `uu_ite` (→ `moderation_uu_ite_list`). A request carrying a valid `__Host-admin_session` SHALL render the editor sub-surface for that list. Serving the page SHALL be strictly read-only — it MUST NOT publish to Remote Config and MUST NOT write an `admin_actions_log` row. Any authenticated admin role MAY view the page. An unknown `{list}` value SHALL be rejected (`404`) without disclosing flag state.

#### Scenario: Authenticated request renders the current list
- **WHEN** an authenticated admin requests `GET /admin/feature-flags/wordlists/profanity`
- **THEN** the response is `200` and renders the current `moderation_profanity_list` entries with the Server-template version

#### Scenario: Serving the editor mutates nothing
- **WHEN** an authenticated admin loads `GET /admin/feature-flags/wordlists/uu_ite`
- **THEN** no Remote Config publish occurs AND no `admin_actions_log` row is written

#### Scenario: Unauthenticated request redirects to the login page
- **WHEN** a request without a valid admin session calls `GET /admin/feature-flags/wordlists/profanity`
- **THEN** the response is a redirect to `/admin/login` and no list content is disclosed

#### Scenario: An unknown list slug is rejected
- **WHEN** an authenticated admin requests `GET /admin/feature-flags/wordlists/notalist`
- **THEN** the response is `404` and no list content is disclosed

### Requirement: The editor renders entries with search, the staging affordances, and a diff preview

The editor SHALL render the list's current entries (searchable/filterable, suitable for 300+ entries), the current template version, the acting admin's remaining wordlist-edit quota, an add-single control, a bulk-CSV import control, and a per-entry remove control. Pending changes SHALL be staged into a previewed diff showing the count of entries added, the count removed, and the resulting total before any publish. All rendered entries and values SHALL be HTML-escaped.

#### Scenario: Entries render searchable
- **WHEN** an admin loads the editor for a list with hundreds of entries
- **THEN** the page renders the entries with a client-usable search/filter affordance and the current entry count

#### Scenario: An entry containing markup is HTML-escaped
- **WHEN** a rendered entry contains HTML metacharacters
- **THEN** the rendered output is HTML-escaped and not interpreted as markup

#### Scenario: The diff preview reflects staged changes
- **WHEN** an admin stages two additions and one removal
- **THEN** the preview shows added = 2, removed = 1, and the resulting total = current count + 1

### Requirement: A staged publish writes the new list to the Server template with a mandatory reason and exactly one audit row

A state-changing write SHALL target a single list, carry the resulting entries and a non-blank free-text `reason`, and on success publish the new list to the Remote Config **Server** template and write exactly one immutable `admin_actions_log` row with `action_type = 'moderation_wordlist_edited'`, a diff summary (the affected list, before/after entry counts, added/removed counts, and the before/after template version) in `before_state`/`after_state`, and the supplied `reason`. The audit row and the publish SHALL be consistent — a write that does not publish MUST NOT leave an audit row.

#### Scenario: A valid publish publishes and audits exactly once
- **WHEN** an authorized admin submits a staged edit for `moderation_profanity_list` with a non-blank reason and the gates pass
- **THEN** the new list is published to the Server template AND exactly one `admin_actions_log` row is written with `action_type = 'moderation_wordlist_edited'`, a diff summary in `before_state`/`after_state`, and the supplied reason

#### Scenario: A write with a blank or missing reason is rejected
- **WHEN** an admin submits a staged edit with an empty or whitespace-only reason
- **THEN** the write is rejected with a validation error AND no Remote Config publish occurs AND no `admin_actions_log` row is written

#### Scenario: A no-op write (resulting list unchanged) is rejected without publish or audit
- **WHEN** an admin submits a staged edit whose normalized resulting list is identical to the current Server-template list
- **THEN** the write is rejected as a no-op AND no publish occurs AND no `admin_actions_log` row is written

### Requirement: A publish that would leave a list empty is rejected

The editor SHALL reject any publish whose resulting list contains zero entries, inline with a validation error, performing no publish and writing no audit row. An empty Remote Config array is treated by the moderation reader as loader failure (cascade to fallback + Sentry WARN), so the editor MUST NOT be a path to an empty list.

#### Scenario: Removing every entry is rejected
- **WHEN** an admin stages the removal of all remaining entries and submits the publish
- **THEN** the write is rejected with a validation error explaining the list cannot be emptied AND no publish occurs AND no `admin_actions_log` row is written

#### Scenario: A submitted empty resulting list is rejected
- **WHEN** a write arrives whose resulting normalized list is empty (e.g., the only staged entries were all blank/duplicate and dropped)
- **THEN** the write is rejected with no publish and no audit row

### Requirement: Entries are normalized to the matcher contract before publish

Before diffing or publishing, every entry SHALL be trimmed, lowercased using the Indonesian locale (diacritics preserved, matching the `KeywordMatcher` contract), and the resulting list de-duplicated. Blank or whitespace-only entries SHALL be rejected/dropped. An entry exceeding the per-entry length cap (100 characters) SHALL be rejected; a resulting list exceeding the list-size cap (10000 entries) SHALL be rejected — each inline, with no publish and no audit row.

#### Scenario: A mixed-case entry is lowercased with diacritics preserved
- **WHEN** an admin adds the entry `"Dünia"`
- **THEN** the staged entry is normalized to `"dünia"` (lowercased, the `ü` diacritic preserved)

#### Scenario: Duplicate entries collapse to one
- **WHEN** an admin's staged additions include the same keyword twice (or a keyword already present after normalization)
- **THEN** the resulting list contains that keyword exactly once

#### Scenario: A blank entry is dropped
- **WHEN** an admin submits an add containing only whitespace
- **THEN** the blank entry is not added to the list

#### Scenario: An over-length entry is rejected
- **WHEN** an admin submits an entry longer than 100 characters
- **THEN** the write is rejected with a validation error AND no publish occurs

#### Scenario: An over-cap list is rejected
- **WHEN** a staged edit would produce a list with more than 10000 entries
- **THEN** the write is rejected with a validation error AND no publish occurs AND no audit row is written

### Requirement: Bulk-CSV import skips duplicates and comment/blank lines with a report

The bulk import SHALL accept newline- or comma-separated entries, normalize them per the entry-normalization rules, skip entries already present in the list, skip blank lines, and skip leading-`#` comment lines (Tier-3 repo-file syntax that is not a Remote Config array entry). The import SHALL report the counts of entries added, duplicates skipped, and comment/blank lines skipped. Import only stages the additions into the diff; it does not itself publish.

#### Scenario: Import skips duplicates with a report
- **WHEN** an admin imports a CSV containing 5 new entries and 3 already present
- **THEN** the 5 new entries are staged AND the report shows 5 added, 3 duplicates skipped

#### Scenario: Import skips comment and blank lines
- **WHEN** an admin imports content containing `# header`, a blank line, and 2 keyword lines
- **THEN** only the 2 keyword lines are staged AND the report shows the `#` comment line and the blank line skipped

### Requirement: Wordlist writes are role-gated to owner/admin

Wordlist publishes SHALL require the acting admin to hold the `owner` or `admin` role. A `moderator` (or any lesser role) attempting a write SHALL be rejected with `403`, with no publish and no `moderation_wordlist_edited` audit row. Viewing the editor (GET) remains available to any authenticated admin role.

#### Scenario: A moderator write is rejected
- **WHEN** an admin holding only the `moderator` role submits a wordlist publish
- **THEN** the response is `403` AND no publish occurs AND no `moderation_wordlist_edited` audit row is written

#### Scenario: An owner or admin write is permitted
- **WHEN** an admin holding `owner` or `admin` submits an otherwise-valid wordlist publish
- **THEN** the write proceeds through the remaining gates

#### Scenario: A moderator may still view the editor
- **WHEN** an admin holding only `moderator` requests `GET /admin/feature-flags/wordlists/profanity`
- **THEN** the editor renders read-only with no write controls enabled for that role

### Requirement: Wordlist writes are CSRF-protected

Every wordlist publish SHALL require a valid `X-CSRF-Token` matching the session's `csrf_token_hash`. A missing or mismatched token SHALL return `403`, perform no publish, and (on mismatch) write an `admin_csrf_violation` audit row per the established admin CSRF contract. This `admin_csrf_violation` security-audit row is the one intended exception to the "no audit row on rejection" rule the other write gates (role, rate-limit, validation, stale-version, empty-list) follow.

#### Scenario: A write without a CSRF token is rejected
- **WHEN** a wordlist publish arrives without an `X-CSRF-Token` header
- **THEN** the response is `403` AND no Remote Config publish occurs

#### Scenario: A write with a mismatched CSRF token is rejected and audited
- **WHEN** a wordlist publish arrives with an `X-CSRF-Token` that does not match the session
- **THEN** the response is `403` AND an `admin_csrf_violation` audit row is written AND no wordlist publish occurs

### Requirement: Wordlist writes are capped per admin per trailing hour on a distinct bucket

A per-admin rate limit SHALL cap `moderation_wordlist_edited` writes at 10 within the trailing hour, counted from the `admin_actions_log` ledger. This bucket SHALL be distinct from the 5/hour `feature_flag_toggled` cap and the 20/hour destructive-action cap: a wordlist edit SHALL NOT be counted by either of those limiters, and neither SHALL consume the wordlist-edit budget. A write at or over the cap SHALL be rejected with no publish and no audit row (so the rejected attempt does not advance the count). The trailing-hour COUNT and the success-path audit INSERT SHALL execute on a single database connection so the count cannot drift from the ledger it gates.

#### Scenario: The eleventh wordlist write within an hour is rejected
- **WHEN** an admin has 10 `moderation_wordlist_edited` rows within the trailing hour and submits an eleventh wordlist publish
- **THEN** the write is rejected AND no publish occurs AND no `admin_actions_log` row is written

#### Scenario: The tenth wordlist write within an hour succeeds
- **WHEN** an admin has 9 `moderation_wordlist_edited` rows within the trailing hour and submits a tenth valid wordlist publish
- **THEN** the write passes the rate-limit gate AND (the remaining gates passing) the list is published and one audit row is written

#### Scenario: The wordlist bucket is independent of the feature-flag and destructive caps
- **WHEN** an admin has exhausted the 5/hour feature-flag-toggle budget
- **THEN** that exhaustion does not reduce the admin's 10/hour wordlist-edit budget, and vice versa

### Requirement: Publishes use optimistic concurrency to prevent silent clobbering

A wordlist publish SHALL carry the Remote Config Server-template etag observed when the editor was rendered. If the Server template has changed since (a concurrent publish — another wordlist edit or a flag toggle on the same template), the publish SHALL be rejected as stale with a retry prompt, performing no overwrite and writing no audit row.

#### Scenario: A stale publish is rejected, not clobbered
- **WHEN** the Server-template version has advanced since the editor was rendered and an admin submits a wordlist publish against the stale etag
- **THEN** the publish is rejected as stale AND the concurrent change is not overwritten AND no audit row is written

#### Scenario: A current-version publish succeeds
- **WHEN** the submitted etag matches the current Server-template version and the gates pass
- **THEN** the publish succeeds and is audited once

### Requirement: The editor degrades to read-only when Remote Config write credentials are absent

When the service is not configured with Remote Config write credentials, the publish seam SHALL report write-unavailable (typed failure, never a thrown crash). In that state the editor SHALL render the current list read-only with disabled controls and an inline notice, and any attempted write SHALL fail safely with no publish, no audit row, and no `500`.

#### Scenario: Write-unconfigured renders read-only
- **WHEN** Remote Config write credentials are not configured and an admin loads the editor
- **THEN** the page renders the current entries read-only with disabled controls and a notice that writes are unavailable

#### Scenario: A write attempted while write-unconfigured fails safely
- **WHEN** a wordlist publish is submitted while Remote Config write credentials are absent
- **THEN** the request fails safely with no publish AND no `admin_actions_log` row AND no `500` response

### Requirement: The editor mutates only the Remote Config Server template

This capability SHALL mutate only the named Server-template parameter (`moderation_profanity_list` or `moderation_uu_ite_list`). It SHALL NOT modify the Tier-3 repo-committed `moderation/profanity.default.txt` / `moderation/uu_ite.default.txt` fallback files, nor the Tier-4 `content-moderation-fallback-list` Secret Manager slot — those remain on the quarterly legal-review update path. The editor SHALL surface that the repo fallback is updated separately and that edits take effect after the ≤5-minute moderation-list cache TTL.

#### Scenario: A publish writes only the Server-template parameter
- **WHEN** an admin publishes an edit to `moderation_profanity_list`
- **THEN** only the Server-template parameter is updated AND neither the repo `*.default.txt` fallback files nor the `content-moderation-fallback-list` Secret Manager slot is modified
