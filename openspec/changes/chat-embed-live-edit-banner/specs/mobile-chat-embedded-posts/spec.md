## RENAMED Requirements

- FROM: `### Requirement: The card shows an edited-since-shared banner when the post moved past the anchor (gate live; live-edit source deferred)`
- TO: `### Requirement: The card shows an edited-since-shared banner when the live post was edited after the share`

## MODIFIED Requirements

### Requirement: The card shows an edited-since-shared banner when the live post was edited after the share

The context card SHALL show a "diedit sejak dibagikan" banner when the live post's latest-edit timestamp (`editedAt` from `single-post-read`, `GET /api/v1/posts/{post_id}`) is known and differs, compared as an instant, from the snapshot's share-time `editedAt`. Both values are `MAX(post_edits.edited_at)` for the post, and `(post_id, edited_at)` is unique, so they differ exactly when an edit landed after the share. This comparison replaces the share-time `embedded_post_edit_id` anchor, which no read path exposes. In every other case the card SHALL show no banner:

- unedited at share time and still unedited (both absent);
- no edit since the share (equal instants);
- the live state is unknown (the read is in flight, failed, or returned `404` because the viewer can no longer see the post).

The gate SHALL never produce a false positive from missing or unparseable data.

The thread's route-scoped `ChatThreadViewModel` SHALL source the live `editedAt` through the `PostEditFlow.refreshPost` repository seam. It SHALL issue at most one read per distinct shared post id per thread visit, for every live card in the merged list: a non-redacted message whose `embedded_post_id` is non-null, whether it arrived via REST history, an older page, or realtime. A redacted message and a deleted-source card (`embedded_post_id` null) SHALL trigger no read and show no banner.

The gate result SHALL be projected onto the display row inside the ViewModel's single `uiState`, not computed in the composable. The card's displayed content SHALL still come from the immutable snapshot. The live read feeds only the banner.

#### Scenario: A post edited after the share shows the banner
- **GIVEN** an embed message whose snapshot `editedAt` is `2026-06-01T11:00:00Z`
- **AND** the live read of that post returns `editedAt = 2026-06-02T09:00:00Z`
- **WHEN** the thread renders the card
- **THEN** the "diedit sejak dibagikan" banner is shown

#### Scenario: A post unedited at share time but edited since shows the banner
- **GIVEN** an embed message whose snapshot `editedAt` is absent
- **AND** the live read returns a non-null `editedAt`
- **WHEN** the thread renders the card
- **THEN** the banner is shown

#### Scenario: An unchanged post shows no banner
- **GIVEN** an embed message whose snapshot `editedAt` equals the live `editedAt` as an instant, or is absent while the live `editedAt` is also absent
- **WHEN** the thread renders the card
- **THEN** no edited-since-shared banner is shown

#### Scenario: Unknown live state shows no banner
- **GIVEN** an embed message whose live read is still in flight, or returned `Unavailable` (a failure, or a `404` because the viewer can no longer see the post)
- **WHEN** the thread renders the card
- **THEN** no edited-since-shared banner is shown

#### Scenario: Each shared post is read once per thread visit
- **GIVEN** a thread whose merged list holds two live embed messages of the same post P and one of post Q
- **WHEN** the history loads and later rows arrive (an older page or a realtime message embedding P)
- **THEN** exactly one live read is issued for P and one for Q

#### Scenario: Deleted-source and redacted embeds are never read
- **GIVEN** a thread holding a deleted-source card (snapshot present, `embedded_post_id` null) and a redacted embed message
- **WHEN** the thread renders
- **THEN** no live read is issued for either, and neither shows the banner
