## MODIFIED Requirements

### Requirement: The card shows an edited-since-shared banner when the post moved past the anchor (gate live; live-edit source deferred)

The context card SHALL gate a "diedit sejak dibagikan" banner on a pure comparison of the message's `embedded_post_edit_id` anchor against a live latest-edit signal: when they differ the banner SHALL show; an unedited-then-still-unedited post (both signals absent) SHALL show no banner; and an absent (unknown) live signal SHALL show no banner (never a false positive from missing data). This change SHALL ship and unit-test that gate.

The **per-card live-edit fetch that feeds the gate is explicitly DEFERRED** (a follow-up, [#440](https://github.com/aditrioka/nearyou-id/issues/440)): the card renders from the immutable snapshot (no live re-fetch — see the context-card requirement) and the thread fetches no live post-edit state, and the shipped `GET /api/v1/posts/{id}/edits` exposes `editedAt` timestamps + version indices, not `post_edits.id`, so a faithful anchor-vs-live-edit-id comparison is not yet wireable client-side. Until the follow-up wires a live-edit source, the runtime SHALL pass the message's own anchor as the live signal, so the banner stays dormant (never a false positive). A future change MODIFIES this requirement to wire the live source rather than rediscovering the gap (the project's "capture deferred behaviors as explicit spec requirements" rule — the same treatment the `chat-embedded-posts` author-tombstone deferral received, later fulfilled by `embedded-snapshot-author-erasure`).

#### Scenario: Edited-since-shared gate shows the banner when anchor differs from the live signal
- **GIVEN** a context card whose `embedded_post_edit_id` anchor differs from a supplied live latest-edit signal
- **WHEN** the gate is evaluated
- **THEN** the "diedit sejak dibagikan" banner is shown

#### Scenario: Unchanged post (or unknown live signal) shows no banner
- **GIVEN** a context card whose anchor matches the live latest-edit signal (or both are absent, or the live signal is unknown/absent)
- **WHEN** the gate is evaluated
- **THEN** no edited-since-shared banner is shown

#### Scenario: The live-edit source is not wired in this change (deferred negative-guard)
- **GIVEN** the shipped thread renders an embed message whose source post was edited after share time
- **WHEN** the card is shown in the running app (no per-card live-edit fetch is performed)
- **THEN** the runtime passes the message's own anchor as the live signal so the banner stays dormant (no false-positive banner); the live-edit fetch is tracked by follow-up [#440](https://github.com/aditrioka/nearyou-id/issues/440)

