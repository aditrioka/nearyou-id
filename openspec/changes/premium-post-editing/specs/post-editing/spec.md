## ADDED Requirements

### Requirement: Premium users can edit their own post content within the creation window

The system SHALL expose `PATCH /api/v1/posts/{post_id}` allowing a Premium user to replace the text content of a post they authored, provided the post was created within the last 30 minutes and is not soft-deleted. On success the system SHALL return the updated post and SHALL have recorded the prior content as a new edit-history entry. Editing SHALL change `posts.content` and `posts.updated_at` only; the post's location SHALL remain unchanged.

#### Scenario: Premium author edits a fresh post

- **WHEN** a user with `subscription_status = 'premium_active'` sends `PATCH /api/v1/posts/{post_id}` with new valid content for a post they authored 5 minutes ago
- **THEN** the system returns 200 with the updated content
- **AND** `posts.content` is the new content and `posts.updated_at` is refreshed
- **AND** the post's stored location is unchanged
- **AND** exactly one new `post_edits` row exists capturing the **pre-edit** content

#### Scenario: User in billing-retry grace retains edit access

- **WHEN** a user with `subscription_status = 'premium_billing_retry'` edits their own post within the 30-minute window
- **THEN** the edit succeeds (the active billing-grace state retains Premium access)

### Requirement: Editing is restricted to the author within the creation window

The system SHALL reject an edit when the requester is not the post's author, when more than 30 minutes have elapsed since the post's creation, or when the post is soft-deleted. The window SHALL be measured from `posts.created_at`, not from the last edit. To avoid leaking post existence: a non-author requester and a non-existent `post_id` SHALL receive the **same** non-confirming `404` response; the author's own post that exists but is merely outside the 30-minute window SHALL receive a distinct `409 edit_window_expired` (safe to reveal to the owner); the author's own soft-deleted post SHALL receive `404`.

#### Scenario: Non-author cannot edit

- **WHEN** a Premium user sends `PATCH /api/v1/posts/{post_id}` for a post authored by someone else
- **THEN** the system returns `404` and makes no change to the post or `post_edits`
- **AND** the response does not confirm whether the post exists

#### Scenario: Non-existent post is indistinguishable from a non-author rejection

- **WHEN** a Premium user sends `PATCH /api/v1/posts/{post_id}` for a `post_id` that does not exist
- **THEN** the system returns the same `404` a non-author receives (no existence reveal)

#### Scenario: Author's own post outside the window returns a distinct error

- **WHEN** the author sends `PATCH /api/v1/posts/{post_id}` for their own post created 31 minutes ago
- **THEN** the system returns `409 edit_window_expired` and makes no change

#### Scenario: Author's own soft-deleted post is rejected as gone

- **WHEN** the author sends `PATCH /api/v1/posts/{post_id}` for their own post whose `deleted_at` is set
- **THEN** the system returns `404` and makes no change

### Requirement: Post editing requires a Premium subscription

The system SHALL gate editing on the requester's `subscription_status` being one of `premium_active` or `premium_billing_retry`. The gate SHALL be checked before any post lookup (so a Free requester learns nothing about the target post). A Free-tier requester SHALL receive a `403 premium_required` response and the post SHALL be unchanged. The gate SHALL use the same premium-state value set that the other premium gates use (the set that governs the daily-post-cap skip: `premium_active` + `premium_billing_retry`).

#### Scenario: Free user is paywalled

- **WHEN** a user with `subscription_status = 'free'` sends `PATCH /api/v1/posts/{post_id}` for their own fresh post
- **THEN** the system returns `403 premium_required`
- **AND** the post content and `post_edits` are unchanged

### Requirement: Edited content is validated

The system SHALL enforce the existing post content guard (1–280 characters after normalization) on the edit payload, BEFORE consuming moderation or transaction resources. An over-length or empty edit SHALL be rejected with `400` and the post SHALL be unchanged. The system SHALL also reject a no-op edit whose normalized content is identical to the post's current content with `400 no_changes` (no snapshot, no update), so the edit history records only real changes.

#### Scenario: Over-length edit is rejected

- **WHEN** the author submits a 281-character edit
- **THEN** the system returns `400` and the post is unchanged

#### Scenario: Empty edit is rejected

- **WHEN** the author submits an edit whose normalized content is empty
- **THEN** the system returns `400` and the post is unchanged

#### Scenario: No-op identical-content edit is rejected

- **WHEN** the author submits an edit whose normalized content equals the post's current content
- **THEN** the system returns `400 no_changes`
- **AND** no `post_edits` row is created and `posts.updated_at` is unchanged

### Requirement: Edited content is re-moderated before it is persisted

The system SHALL run the edited content through the same moderation pipeline as post creation: the synchronous keyword moderator first, then — only after a successful commit — the asynchronous Layer-3 (Perspective) dispatch. A reject verdict SHALL block the edit; a flag verdict SHALL allow the edit but record a moderation-queue entry in the same transaction. This closes the create-clean-then-edit-toxic laundering path.

#### Scenario: Edit to profane content is rejected

- **WHEN** the author edits their post to content matching the profanity blocklist (reject verdict)
- **THEN** the system returns `400 content_moderated_profanity`
- **AND** the post content and `post_edits` are unchanged

#### Scenario: Reject verdict does not dispatch Layer-3

- **WHEN** an edit yields a reject verdict
- **THEN** no asynchronous Layer-3 moderation is dispatched (there is no committed row to moderate)

#### Scenario: Edit to flagged content is persisted with a moderation-queue entry

- **WHEN** the author edits their post to content that yields a flag verdict
- **THEN** the edit is persisted (content updated, before-edit snapshot recorded)
- **AND** a `moderation_queue` entry for the post is recorded in the same transaction

#### Scenario: Successful edit dispatches asynchronous Layer-3 moderation

- **WHEN** an edit with an allow verdict commits successfully
- **THEN** a fire-and-forget Layer-3 moderation of the new content is dispatched after commit, carrying the request trace context

### Requirement: An edit persists the before-edit snapshot atomically

The system SHALL, within a single database transaction, capture the post's pre-edit content and location as a new append-only `post_edits` row (`post_id`, `content_snapshot`, `location_snapshot`, `edited_by`, `edited_at` defaulted to `clock_timestamp()`) and then update the post. Either both the snapshot and the update commit, or neither does. The `post_edits` table SHALL cascade-delete with its parent post.

#### Scenario: Snapshot and update commit together

- **WHEN** an edit succeeds
- **THEN** the new `post_edits` row holds the content that was live **before** this edit
- **AND** `posts.content` holds the new content

#### Scenario: Transaction failure leaves no partial state

- **WHEN** the `UPDATE posts` step fails after the snapshot insert within the edit transaction
- **THEN** the transaction rolls back and no `post_edits` row is left behind

#### Scenario: Edits cascade-delete with the post

- **WHEN** a post with edit history is hard-deleted
- **THEN** its `post_edits` rows are removed

### Requirement: Concurrent edits to the same post are race-safe

The system SHALL serialize concurrent edits to the same post using a row lock (`SELECT … FOR UPDATE`) and SHALL guarantee temporal-key uniqueness via a unique index on `(post_id, edited_at)`. On the sub-microsecond `unique_violation` edge the system SHALL retry once and, if it still conflicts, return `409` with the message "Coba lagi sebentar."

#### Scenario: Two simultaneous edits do not lose an update

- **WHEN** two edit requests for the same post arrive concurrently
- **THEN** they are applied in a serialized order with each producing its own `post_edits` snapshot (no lost update)

#### Scenario: Temporal collision yields a retryable conflict

- **WHEN** an edit hits the `(post_id, edited_at)` unique constraint and the single retry still collides
- **THEN** the system returns `409` with "Coba lagi sebentar."

### Requirement: Post editing is rate-limited per user

The system SHALL rate-limit the edit endpoint per user, reusing the existing rate-limit infrastructure, so that rapid repeated edits cannot amplify the synchronous moderation I/O and the external Perspective (Layer-3) calls each edit triggers. The specific cap SHALL be a configurable/ops-tunable value (not hard-coded to a literal in business logic). When the cap is exceeded the system SHALL return `429` with a `Retry-After` header and make no change to the post.

#### Scenario: Edit rate limit is enforced

- **WHEN** a user exceeds the configured per-user edit rate limit
- **THEN** the system returns `429` with a `Retry-After` header
- **AND** the post content and `post_edits` are unchanged

### Requirement: Post edit history is readable with chronological version labels

The system SHALL expose `GET /api/v1/posts/{post_id}/edits` returning the post's edit history in chronological order, each entry labelled "Versi ke-N" where N is the 1-based position via `ROW_NUMBER() OVER (PARTITION BY post_id ORDER BY edited_at)`. A post with no edits SHALL return an empty history.

#### Scenario: History lists versions in order

- **WHEN** a permitted viewer requests the history of a post edited twice
- **THEN** the response lists the two snapshots in chronological order labelled "Versi ke-1" and "Versi ke-2"

#### Scenario: Unedited post returns empty history

- **WHEN** a permitted viewer requests the history of a never-edited post
- **THEN** the response is an empty history (200)

### Requirement: Edit history read honours post visibility

The system SHALL resolve the target post through the shadow-ban-safe visible-posts view AND the bidirectional block exclusion (a real `user_blocks` exclusion predicate on the read query, never a lint annotation bypass) before returning history. A viewer who cannot see the post (shadow-banned author they don't own, or a block in either direction) SHALL receive `404`, without confirming the post's existence.

#### Scenario: Blocked viewer cannot read history

- **WHEN** a viewer who is blocked by (or has blocked) the author requests the post's edit history
- **THEN** the system returns `404`

#### Scenario: Shadow-banned author's post history is hidden from others

- **WHEN** a viewer other than the author requests the edit history of a post by a shadow-banned author
- **THEN** the system returns `404`
- **AND** the author themselves can still read their own post's history

### Requirement: Edit history read does not expose raw location

The edit-history read SHALL return only content versions, their version labels, and their edit timestamps. It SHALL NOT return the raw, unfuzzed location stored in `post_edits.location_snapshot` (nor `posts.actual_location`); raw coordinates remain admin/audit-only per the spatial-fuzzing invariant. The history response carries no location field at all (the product surface — the "Riwayat edit" modal — renders content versions only).

#### Scenario: History response omits raw location

- **WHEN** a permitted viewer requests a post's edit history
- **THEN** each returned version contains the content snapshot, its "Versi ke-N" label, and `edited_at`
- **AND** the response contains no raw `location_snapshot` / `actual_location` value

### Requirement: This change delivers no mobile or admin client surface

The backend edit + history endpoints SHALL be the entire scope of this change; the mobile edit/history UI ("Riwayat edit" modal + "Diedit" label) and the admin report-queue "post has edit history" filter SHALL be delivered by separate follow-up changes built against these endpoints. This change SHALL add no `:mobile:app` and no admin-panel code.

#### Scenario: No client UI ships in this change

- **WHEN** this change is implemented
- **THEN** the implementation adds backend routes/service/migration/tests only
- **AND** no `:mobile:app` or admin-panel files are modified

#### Scenario: Follow-up surfaces are tracked, not silently dropped

- **WHEN** the backend capability lands
- **THEN** the deferred mobile edit/history UI (Phase 4 item 13) and the admin edit-history filter (issue #191) remain tracked as explicit follow-up work for a later change to MODIFY
