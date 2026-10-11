## MODIFIED Requirements

### Requirement: Entries are normalized to the matcher contract before publish

Before diffing or publishing, every entry SHALL be trimmed, lowercased using the Indonesian locale (diacritics preserved, matching the `KeywordMatcher` contract), and the resulting list de-duplicated. Blank or whitespace-only entries SHALL be rejected/dropped. An entry exceeding the per-entry length cap (100 characters) SHALL be rejected; a resulting list exceeding the list-size cap (10000 entries) SHALL be rejected — each inline, with no publish and no audit row. Inline rejection applies to submissions within the editor paths' 4 MiB transport limit (`backend-bootstrap` § Global request body size limit); a request body above it is rejected `413 payload_too_large` before the route runs, with no publish and no audit row.

#### Scenario: A mixed-case entry is lowercased with diacritics preserved
- **WHEN** an admin adds the entry `"Dünia"`
- **THEN** the staged entry is normalized to `"dünia"` (lowercased, the `ü` diacritic preserved)

#### Scenario: Duplicate entries collapse to one
- **WHEN** an admin's staged additions include the same keyword twice (or a keyword already present after normalization)
- **THEN** the resulting list contains that keyword exactly once

#### Scenario: Case and diacritic collisions dedup to one entry
- **WHEN** an admin's staged additions include `"Anjïng"` and `"anjïng"`
- **THEN** both normalize to `"anjïng"` (id-locale lowercase, diacritic preserved) AND the resulting list contains that entry exactly once

#### Scenario: A leading-# entry via add-single is a literal keyword
- **WHEN** an admin adds the single entry `"#promo"` via the add-single control (not the bulk-CSV import)
- **THEN** it is normalized to the literal keyword `"#promo"` — the `#`-comment-line stripping applies to bulk-CSV import only, not to add-single

#### Scenario: A blank entry is dropped
- **WHEN** an admin submits an add containing only whitespace
- **THEN** the blank entry is not added to the list

#### Scenario: An over-length entry is rejected
- **WHEN** an admin submits an entry longer than 100 characters
- **THEN** the write is rejected with a validation error AND no publish occurs

#### Scenario: An over-cap list is rejected
- **WHEN** a staged edit would produce a list with more than 10000 entries
- **THEN** the write is rejected with a validation error AND no publish occurs AND no audit row is written

#### Scenario: A body above the transport limit is rejected 413 before the route runs
- **WHEN** a `POST /admin/feature-flags/wordlists/{list}` or `POST /admin/feature-flags/wordlists/{list}/preview` declares a `Content-Length` above 4 MiB
- **THEN** the response is `413` with error code `payload_too_large`, the CSRF and role gates and normalization never run, and nothing is published or audited
