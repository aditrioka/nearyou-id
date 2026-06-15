## ADDED Requirements

### Requirement: POST /api/v1/posts accepts an optional owner-validated image_id

`POST /api/v1/posts` SHALL accept an optional `image_id` field. When present, the system SHALL validate — against the `image_uploads` ledger — that the `image_id` exists, was uploaded by the caller (`uploader_user_id = caller`), and is not already attached (`status = 'uploaded'`). On a valid attach the system SHALL persist the `image_id` into the existing `posts.image_id` column and atomically flip the ledger row to `status = 'attached'`. At most one image SHALL be attached per post. When `image_id` is absent the endpoint SHALL behave exactly as before (a text-only post), with an unchanged success-response field set. Read-path surfacing of the attached image is out of scope (deferred to `mobile-image-upload-ui`).

#### Scenario: Attach a caller-owned uploaded image
- **WHEN** a Premium caller creates a post with an `image_id` they uploaded whose ledger `status = 'uploaded'`
- **THEN** the post is created with `posts.image_id` set, the `image_uploads` row flips to `status = 'attached'`, and the endpoint returns 201

#### Scenario: Reject an image owned by another user
- **WHEN** a caller creates a post with an `image_id` whose `image_uploads.uploader_user_id` is a different user
- **THEN** the system rejects the request, creates no post, and leaves the ledger row unchanged

#### Scenario: Reject a non-existent image_id
- **WHEN** a caller creates a post with an `image_id` that has no `image_uploads` row
- **THEN** the system rejects the request and creates no post

#### Scenario: Reject an already-attached image
- **WHEN** a caller creates a post with an `image_id` whose ledger `status` is already `'attached'`
- **THEN** the system rejects the request and creates no second post

#### Scenario: Atomic attach — post INSERT failure rolls back the ledger flip
- **WHEN** the post INSERT fails after the ledger row was selected for attach
- **THEN** the transaction rolls back and the `image_uploads` row remains `status = 'uploaded'`

#### Scenario: Text-only post unchanged
- **WHEN** a caller creates a post with no `image_id`
- **THEN** behavior and the success-response field set are identical to the pre-change text-post path (no image field added to the response)
