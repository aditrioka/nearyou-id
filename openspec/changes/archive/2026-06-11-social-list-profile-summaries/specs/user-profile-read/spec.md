# user-profile-read — Delta Specification

## MODIFIED Requirements

### Requirement: Follower and following counts are raw totals

`followerCount` MUST equal the total number of `follows` edges whose followee is the target, and `followingCount` MUST equal the total number of `follows` edges whose follower is the target. These counts MUST NOT be viewer-block-filtered AND MUST NOT be visibility-filtered — they are deliberately asymmetric with the `/followers` and `/following` list endpoints (which are bidirectionally viewer-block-filtered AND, as of `social-list-profile-summaries`, visibility-filtered via `visible_users`), because a follower count is a public aggregate: per-viewer filtering would leak block state via count deltas, and visibility filtering would make every shadow-ban/unban visibly twitch public counters.

#### Scenario: Counts reflect the follows graph
- **WHEN** target T is followed by 3 users and follows 5 users, and an authenticated viewer reads T's profile
- **THEN** the response has `followerCount = 3` and `followingCount = 5`

#### Scenario: Counts are not viewer-block-filtered
- **WHEN** target T has 3 followers, one of whom (X) has blocked the viewer V, and V reads T's profile
- **THEN** `followerCount` is still `3` (the raw total; the blocked follower is not subtracted from the count even though X would be excluded from the `/followers` list)

#### Scenario: Counts are not visibility-filtered
- **WHEN** target T has 3 followers, one of whom is shadow-banned, and an authenticated viewer reads T's profile
- **THEN** `followerCount` is still `3` even though the `/followers` list returns only the 2 visible members (deliberate count/list asymmetry, design D1)
