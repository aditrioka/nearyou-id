## MODIFIED Requirements

### Requirement: The embed message anchors the post's version at share time

When building an embed the server SHALL set `embedded_post_edit_id` to the source post's most recent `post_edits.id` at send time, or NULL when the post has never been edited. This anchor is the "version-at-share-time" handle: it names the exact edit row the share captured, for the edit-history navigation. The mobile edited-since-shared banner does NOT read it. The banner compares the snapshot's share-time `editedAt` with the live `single-post-read` `editedAt` (`mobile-chat-embedded-posts`). The two signals agree because `(post_id, edited_at)` is unique on `post_edits`.

#### Scenario: Unedited post anchors to NULL
- **GIVEN** post P has no `post_edits` rows
- **WHEN** S shares P
- **THEN** the inserted row's `embedded_post_edit_id` is NULL

#### Scenario: Edited post anchors to the latest edit id
- **GIVEN** post P has one or more `post_edits` rows with the most recent being E
- **WHEN** S shares P
- **THEN** the inserted row's `embedded_post_edit_id` equals E
