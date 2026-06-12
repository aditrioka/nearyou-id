# following-timeline (delta)

## ADDED Requirements

### Requirement: Following carries NO own-content self-arm (deliberate)

`shadow-ban-feed-self-visibility` makes Nearby and Global viewer-aware (an own-content self-arm so shadow-banned authors keep seeing their own posts). The Following query MUST NOT gain such a self-arm, and MUST remain exactly the canonical shape of the "Canonical query joins visible_posts, follows, and excludes blocks bidirectionally" requirement.

Rationale (pinned so a future editor doesn't "complete" the trio): self-follow is impossible at two layers (`follows_no_self_follow CHECK (follower_id <> followee_id)` in V6; app-layer 400 `cannot_follow_self` per `follow-system`), so the `author_id IN (SELECT followee_id FROM follows WHERE follower_id = :viewer)` filter can never match the viewer's own posts — for ANY user, banned or not. The shadow-ban illusion standard is "the feed shows exactly what it would show if the user weren't banned"; for Following that is own posts ABSENT. Adding a self-arm would invert the oracle: a shadow-banned user would see their own posts in Following where a normal user sees none.

#### Scenario: Shadow-banned viewer's own posts absent from Following (parity with normal users)
- **WHEN** caller A has `is_shadow_banned = TRUE`, A has recent non-deleted posts, AND A requests `GET /api/v1/timeline/following`
- **THEN** A's own posts do NOT appear in the response — identical to a non-banned user's Following feed, which never contains own posts

#### Scenario: Following SQL literal carries no self-arm
- **WHEN** inspecting the Following SQL literal in `JdbcPostsFollowingRepository`
- **THEN** it contains no `UNION ALL`, no raw `FROM posts` arm, and no `author_id = :viewer` self-predicate — the single `FROM visible_posts` shape with the follows filter and bidirectional block exclusion is unchanged
