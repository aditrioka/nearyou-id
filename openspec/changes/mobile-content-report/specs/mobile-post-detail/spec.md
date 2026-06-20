## RENAMED Requirements

- FROM: `### Requirement: Block and report kebab actions are deferred`
- TO: `### Requirement: Block kebab action is deferred`

## MODIFIED Requirements

### Requirement: Block kebab action is deferred

This spec SHALL NOT add any **block** affordance (post or reply kebab "Blokir") to `PostDetailScreen` or the reply cards. Block-from-post-context (`docs/06-Security-Privacy.md`:237 — "Block from the profile or a post context menu") is a separate feature (the `user_blocks` backend + a confirmation modal) and remains deferred; the profile-level block is already shipped via `mobile-profile`. **Report** affordances are no longer deferred: post and reply reporting are now specified by the `mobile-content-report` capability together with the "Post header exposes a report affordance for non-authored posts" and "Each reply row exposes a report affordance" requirements below. GitHub issue [#200](https://github.com/aditrioka/nearyou-id/issues/200) (label `follow-up`), originally `mobile-post-detail-block-report-kebab`, tracks the remaining block deferral.

#### Scenario: No block affordance is present
- **WHEN** inspecting `PostDetailScreen.kt` and the reply-card composables
- **THEN** there is NO "Blokir" control, block kebab item, or block API call (report affordances MAY be present per the requirements below)

#### Scenario: Follow-up issue tracks the block deferral
- **WHEN** inspecting the project's open GitHub issues (label `follow-up`)
- **THEN** GitHub issue [#200](https://github.com/aditrioka/nearyou-id/issues/200) (label `follow-up`) tracks the remaining block-from-post-context deferral

## ADDED Requirements

### Requirement: Post header exposes a report affordance for non-authored posts

`PostDetailScreen` SHALL expose a "Laporkan" report affordance in the post header, shown ONLY when the viewer does NOT author the post (the server-authoritative `isAuthor` boolean — mirroring the existing Edit-affordance gate). Activating it SHALL open the shared report dialog (`mobile-content-report`) targeting `target_type = "post"`, `target_id = <the post id>`. The affordance SHALL NOT appear for the viewer's own post. The affordance SHALL NOT introduce any author UUID or coordinate into the rendered tree (the "No author identifier or coordinate is rendered or logged" requirement is preserved).

#### Scenario: Report affordance shown on a non-authored post
- **GIVEN** the post header renders with `isAuthor = false`
- **WHEN** the header is inspected
- **THEN** a "Laporkan" report affordance is present AND activating it opens the report dialog targeting the post

#### Scenario: Report affordance hidden on the viewer's own post
- **GIVEN** the post header renders with `isAuthor = true`
- **WHEN** the header is inspected
- **THEN** no "Laporkan" post affordance is present (the Edit affordance is shown instead, per the existing header requirement)

#### Scenario: Completing the dialog submits the post target
- **WHEN** the viewer completes the report dialog for the post
- **THEN** a `POST /api/v1/reports` with `target_type = "post"` and the post id is issued AND the outcome is handled per `mobile-content-report`

### Requirement: Each reply row exposes a report affordance

Each reply row in `PostDetailScreen` SHALL expose a "Laporkan" report affordance that opens the shared report dialog (`mobile-content-report`) targeting `target_type = "reply"`, `target_id = <the reply id>`. The affordance SHALL NOT render or rely on reply author identity — the reply wire carries `author_id` but it is never rendered and never used to gate the affordance; only the reply `id` is used as `target_id`. The "No author identifier or coordinate is rendered or logged" requirement is preserved (no author UUID appears in the rendered tree, logs, or the report request).

#### Scenario: Reply row exposes a report affordance
- **WHEN** a reply card renders
- **THEN** it exposes a "Laporkan" report affordance AND activating it opens the report dialog targeting that reply's id

#### Scenario: Reply report uses the reply id only, never author identity
- **GIVEN** a reply with `author_id = "11111111-1111-1111-1111-111111111111"` and `id = "R"`
- **WHEN** the report dialog is opened from that reply and submitted
- **THEN** the request `target_id` is `"R"` AND no rendered node, log line, or request field contains `"11111111-1111-1111-1111-111111111111"`

### Requirement: PostDetailScreen report affordances are covered by tests

The Robolectric `PostDetailScreenTest` SHALL additionally cover: the post report affordance present on a non-authored post and absent on the viewer's own post; a reply-row report affordance present; and the dialog submit → success-message path (via a fake report seam). These additions remain within the existing Release-variant `*ScreenTest` exclude and pass under `:mobile:app:testDevReleaseUnitTest`.

#### Scenario: Report affordance tests exist and are discoverable
- **WHEN** running `./gradlew :mobile:app:testDevDebugUnitTest`
- **THEN** `PostDetailScreenTest` includes tests asserting the non-authored-post report affordance, the own-post report-affordance absence, the reply-row report affordance, and the dialog submit → success-message path
