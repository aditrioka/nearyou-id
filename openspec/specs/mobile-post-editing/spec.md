# mobile-post-editing Specification

## Purpose

The `mobile-post-editing` capability is the `:mobile:app` surface for the shipped premium post-editing backend (`PATCH /api/v1/posts/{post_id}` + `GET /api/v1/posts/{post_id}/edits`). On the post-detail screen it adds: an Edit affordance for the viewer's own post within the 30-minute window (gated by the server-authoritative `isAuthor` from `single-post-read`), a prefilled edit screen reusing the post-creation content editor, reactive premium gating (a `403 premium_required` raises the "Aktifkan Premium" upsell — no client-side premium flag) with the full edit error-contract mapped to Bahasa Indonesia UX, a "Diedit [relative time]" label driven by the `editedAt` projection, and a screen-local "Riwayat edit" history modal listing the chronological "Versi ke-N" content versions (no location). Post-detail reads this edit state via a `single-post-read` refresh on each resume, degrading silently on failure. The timeline-card edited badge and the chat context-card edit-history navigation are tracked follow-up work, not part of this capability.

## Requirements
### Requirement: The post-detail screen offers an Edit affordance on the viewer's own recent post

The `:mobile:app` post-detail screen SHALL present an **Edit** affordance for a post when, and only when, the post was authored by the current viewer AND the post is within the 30-minute edit window measured from its `createdAt`. Authorship SHALL be determined by the server-authoritative `isAuthor` flag from the `single-post-read` projection (the client cannot otherwise determine ownership — neither the post-detail route payload nor the projection carries `author_id`); the 30-minute window SHALL be a client-side hint computed from `createdAt`. For a post authored by another user (`isAuthor` false or not yet resolved), or the viewer's own post whose `createdAt` is more than 30 minutes ago, the Edit affordance SHALL NOT be shown. The window hint is advisory only; the backend `PATCH /api/v1/posts/{post_id}` remains the authoritative gate, so a boundary case (clock skew) that surfaces the affordance and then receives `409 edit_window_expired` SHALL be handled gracefully (see the error-mapping requirement) rather than crashing or showing a generic failure.

#### Scenario: Own fresh post shows the Edit affordance

- **WHEN** the viewer opens post-detail for their own post created 5 minutes ago
- **THEN** the Edit affordance is shown

#### Scenario: Own stale post hides the Edit affordance

- **WHEN** the viewer opens post-detail for their own post created 45 minutes ago
- **THEN** the Edit affordance is not shown

#### Scenario: Another user's post never shows the Edit affordance

- **WHEN** the viewer opens post-detail for a post authored by someone else
- **THEN** the Edit affordance is not shown regardless of the post's age

### Requirement: Post-detail reads edit state from a single-post-read refresh

Because the post-detail screen is otherwise driven by its navigation route payload (which carries no edit state), it SHALL fetch the `single-post-read` projection (`GET /api/v1/posts/{post_id}`) on each resume — the first open AND the return from the edit screen — to obtain the current `content` (freshening a post edited elsewhere or just now), `editedAt` (the "Diedit" label signal), and `isAuthor` (the Edit-affordance gate). A failed refresh SHALL degrade silently: the header keeps its route-payload content and the "Diedit" label / Edit affordance stay hidden — it SHALL NOT error the post-detail surface.

#### Scenario: A successful edit is reflected on return to post-detail

- **WHEN** the viewer edits their post and the edit screen pops back to post-detail
- **THEN** post-detail's resume refresh re-reads the post and renders the updated content and the "Diedit" label

#### Scenario: A failed refresh degrades silently

- **WHEN** the single-post-read refresh fails (a non-200 or transport error)
- **THEN** post-detail keeps its route-payload content and shows no "Diedit" label and no Edit affordance (no error chrome)

### Requirement: Selecting Edit opens a prefilled editor that submits a content edit

Selecting the Edit affordance SHALL open a dedicated edit destination prefilled with the post's current content, reusing the post-creation content editor (the 280-character limit and the same empty/over-length client validation). The editor SHALL submit the new content to `PATCH /api/v1/posts/{post_id}`. On a `200` response the flow SHALL return to post-detail rendering the updated content and the "Diedit" label, without requiring a manual refresh. The edit SHALL change content only — the editor SHALL NOT expose a location or any other post field.

#### Scenario: Successful edit updates the post in place

- **WHEN** the viewer edits their fresh post to new valid content and submits, and the backend returns `200`
- **THEN** the flow returns to post-detail showing the new content
- **AND** the "Diedit" label is shown

#### Scenario: Client validation mirrors post creation

- **WHEN** the viewer clears the editor or types past 280 characters
- **THEN** the submit action is blocked by the same client-side validation post creation uses (no network call is made)

#### Scenario: The editor exposes content only

- **WHEN** the edit destination is shown
- **THEN** it presents only the text content field (no location control or other post field)

### Requirement: Premium gating is reactive on the backend 403

The edit flow SHALL NOT read a client-side premium flag before allowing an edit attempt; it SHALL attempt the `PATCH` and react to the response, mirroring the shipped `mobile-search` reactive-gate house pattern. When the submit returns `403 premium_required`, the flow SHALL present the shared Premium upsell ("Aktifkan Premium" CTA, reusing the existing upsell component) rather than a generic error, and the post SHALL remain unchanged in the UI.

#### Scenario: Free-tier user is shown the Premium upsell

- **WHEN** a Free-tier viewer submits an edit and the backend returns `403 premium_required`
- **THEN** the Premium upsell is presented (the "Aktifkan Premium" CTA), not a generic error
- **AND** the displayed post content is unchanged

#### Scenario: No premium pre-check gates the attempt

- **WHEN** the viewer selects Edit
- **THEN** the editor opens and the attempt is made without first reading any client-side premium/entitlement flag (the 403 path is the gate)

### Requirement: The full backend edit error contract maps to distinct user-facing outcomes

The edit flow SHALL map each documented `PATCH /api/v1/posts/{post_id}` failure to a distinct, localized (Bahasa Indonesia) user-facing outcome, and in every failure case SHALL leave the displayed post content unchanged:

- `409 edit_window_expired` → an "edit window has passed" message (safe to reveal to the owner)
- `400` over-length / empty → inline editor validation (no separate error chrome)
- `400 no_changes` → a "no changes to save" message
- `400 content_moderated_profanity` (and other `content_moderated_*` codes) → a content-rejected-by-moderation message
- `409` temporal-collision (the retryable conflict) → the message "Coba lagi sebentar."
- `429` → a rate-limit message that respects the `Retry-After` hint
- `404` → a generic not-found message

#### Scenario: Expired window is distinguished from a generic error

- **WHEN** an edit submit returns `409 edit_window_expired`
- **THEN** the flow shows the window-expired message (distinct from the temporal-collision and not-found messages)

#### Scenario: Moderation rejection is surfaced as such

- **WHEN** an edit submit returns `400 content_moderated_profanity`
- **THEN** the flow shows the moderation-rejected message and the displayed content is unchanged

#### Scenario: Temporal collision shows the retry copy

- **WHEN** an edit submit returns the retryable `409` temporal-collision
- **THEN** the flow shows "Coba lagi sebentar."

#### Scenario: Rate limit respects Retry-After

- **WHEN** an edit submit returns `429` with a `Retry-After` header
- **THEN** the flow shows a rate-limit message and does not silently retry before the hinted delay

#### Scenario: No-op edit shows the no-changes message

- **WHEN** an edit submit returns `400 no_changes`
- **THEN** the flow shows the "no changes to save" message (distinct from the over-length/empty inline validation and the generic not-found message) and the displayed content is unchanged

#### Scenario: Not-found renders the generic message

- **WHEN** an edit submit returns `404`
- **THEN** the flow shows the generic not-found message (distinct from `409 edit_window_expired` and the moderation/temporal/rate-limit messages) and the displayed content is unchanged

### Requirement: Edited posts display a "Diedit" label in post-detail

A post that the post-detail projection reports as edited (its `editedAt` is present) SHALL display a "Diedit [relative time]" label, where the relative time is rendered from `editedAt`. A post with no `editedAt` (never edited) SHALL NOT display the label. The label is the entry point to the edit-history modal.

#### Scenario: Edited post shows the Diedit label

- **WHEN** post-detail renders a post whose projection carries a non-null `editedAt`
- **THEN** a "Diedit [relative time]" label is shown

#### Scenario: Unedited post shows no Diedit label

- **WHEN** post-detail renders a post whose projection has no `editedAt`
- **THEN** no "Diedit" label is shown

### Requirement: Tapping the Diedit label opens the "Riwayat edit" history modal

Tapping the "Diedit" label SHALL open a screen-local "Riwayat edit" modal that loads `GET /api/v1/posts/{post_id}/edits` and lists the content versions in chronological order, each labelled "Versi ke-N" (1-based, as returned by the endpoint). The modal SHALL render content, version label, and edit time only — never a location field (matching the endpoint's no-location contract). The modal SHALL present a loading state while fetching, an empty state for a post with no recorded versions, and an error state (with a retry affordance) on a failed load.

The client history DTO MUST bind the endpoint's actual wire keys, which are **snake_case** — `version_label`, `content`, `edited_at` — as emitted by `PostEditRoutes.kt` (a hand-built JSON object, NOT the stale spec JSON example). A client DTO derived from camelCase would silently fail to parse (the PR #128 casing-drift precedent); a negative-guard test SHOULD assert the snake_case binding.

#### Scenario: History versions bind the snake_case wire keys

- **WHEN** the history modal deserializes a `GET /api/v1/posts/{post_id}/edits` response
- **THEN** each version binds from the snake_case keys `version_label`, `content`, and `edited_at` (a camelCase `editedAt`/`versionLabel` body does NOT populate the version)

#### Scenario: History modal lists versions in order

- **WHEN** the viewer taps the Diedit label for a post edited twice and the history loads
- **THEN** the modal lists the two snapshots in chronological order labelled "Versi ke-1" and "Versi ke-2"

#### Scenario: History modal renders no location

- **WHEN** the history modal renders a version
- **THEN** it shows the content snapshot, its "Versi ke-N" label, and the edit time, and shows no location/coordinate value

#### Scenario: History load failure shows a retry state

- **WHEN** the `GET /api/v1/posts/{post_id}/edits` call fails
- **THEN** the modal shows an error state with a retry affordance (not a crash or blank modal)

### Requirement: This change does not add a timeline-card edited indicator

This change SHALL NOT render an edited indicator (a "Diedit" badge) on the Nearby, Following, or Global timeline cards. Surfacing the edited state on timeline cards requires the perf-sensitive timeline queries and DTOs to carry the indicator and is intentionally deferred to a follow-up change that will MODIFY the timeline capabilities. The deferral SHALL be tracked as a `follow-up` issue, not silently dropped.

#### Scenario: Timeline cards carry no edited indicator in this change

- **WHEN** a previously-edited post appears on a Nearby / Following / Global timeline card
- **THEN** the card shows no "Diedit" badge (the edited surface exists only in post-detail in this change)

#### Scenario: The deferred timeline-card badge is tracked

- **WHEN** this change is implemented
- **THEN** the timeline-card edited-badge work remains tracked as explicit follow-up work (a filed `follow-up` issue) for a later change to MODIFY the timeline capabilities

### Requirement: This change does not add chat context-card edit-history navigation

This change SHALL NOT add edit-history navigation or the "post edited after you chatted" banner to chat context cards (the embedded-post surface in chat). That behavior (`docs/03` § Chat Context Card UX; Phase 4 item 14) is deferred to a separate follow-up change and SHALL be tracked as a `follow-up` issue, not silently dropped.

#### Scenario: Chat embeds are unchanged by this change

- **WHEN** a chat message embeds a post that has edit history
- **THEN** the chat context card is unchanged by this change (no edit-history banner or navigation added)

#### Scenario: The deferred chat edit-nav is tracked

- **WHEN** this change is implemented
- **THEN** the chat context-card edit-history navigation remains tracked as explicit follow-up work (a filed `follow-up` issue) for a later change

