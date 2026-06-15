# mobile-profile — Delta Specification

## RENAMED Requirements

- FROM: `### Requirement: Follower and following counts render as static numbers`
- TO: `### Requirement: Follower and following counts are tappable entries to the member lists`

## MODIFIED Requirements

### Requirement: Follower and following counts are tappable entries to the member lists

`ProfileScreen` SHALL render `followerCount` and `followingCount` as numbers with a `stringResource` label, and each count SHALL be a **tappable control** that navigates to the follower/following member-list surface (`mobile-follow-lists`) opened at the matching tab: tapping the **follower** count emits navigation to `FollowListRoute(userId, initialTab = Followers)` and tapping the **following** count emits navigation to `FollowListRoute(userId, initialTab = Following)`. Each count control SHALL expose a `stringResource` content description (no color-only or icon-only affordance). The tappable counts apply to **both** the self read and the other-user read (a user may browse their own and others' follower/following lists; the backend filters each list against the viewer regardless of owner). The displayed count VALUES remain a read snapshot of the raw public aggregate and SHALL NOT be mutated locally (unchanged from the prior behavior — a follow/unfollow elsewhere does not adjust the number here). The `userId` passed to `FollowListRoute` is the profile's resolved id (the same id the profile read used) and SHALL NOT be rendered as a UI string. The navigation SHALL be emitted to the host (the `mobile-home-tab-host` root-stack push mechanism), not performed by `ProfileScreen` directly.

#### Scenario: Counts are tappable and navigate to the matching list tab

- **WHEN** the follower count is activated, and again when the following count is activated
- **THEN** the follower-count activation emits navigation to `FollowListRoute(userId, initialTab = Followers)` AND the following-count activation emits navigation to `FollowListRoute(userId, initialTab = Following)` AND each count is a clickable node in the semantics tree carrying a `stringResource` content description

#### Scenario: Tapping a count does not mutate the displayed value

- **GIVEN** a loaded profile with `followerCount = 12` and `followingCount = 34`
- **WHEN** either count is activated
- **THEN** the rendered count values remain 12 and 34 (only a navigation intent is emitted; no local count mutation)

#### Scenario: Both self and other-user reads expose the tappable counts

- **WHEN** `ProfileScreen` is rendered with `isSelf = true` and again with `isSelf = false`
- **THEN** in both renders the follower and following counts are tappable controls wired to `FollowListRoute` with the profile's `userId` (the counts entry is independent of the follow/block/report actions, which remain `isSelf = false` only)
