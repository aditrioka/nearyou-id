## MODIFIED Requirements

### Requirement: By-id post reads never gate the header's first paint

The post-detail surface SHALL be fed by the `single-post-read` capability (`GET /api/v1/posts/{post_id}`; the backend half of GitHub issue [#202](https://github.com/aditrioka/nearyou-id/issues/202), now closed) in exactly the three shipped ways below — the screen's own resume read, the deep-link lookup done before the push, and the search-result lookup done before the push — and none SHALL block the header's first paint — the header is always first built from the `PostDetailRoute` nav args (per § "The post header renders from nav args without a single-post re-fetch"):

- **Resume-time freshness read** (owned by `mobile-post-editing` § "Post-detail reads edit state from a single-post-read refresh"): on each resume the screen fetches the minimal projection via `SinglePostApiClient.fetchPost` to refresh the displayed content and resolve `editedAt` / `isAuthor` / `authorUserId`; a non-200 or transport failure degrades silently to the payload (no error state).
- **No-card entry** (owned by `mobile-notifications-list` § "A post-target notification resolves to a PostDetailTarget via the full-projection single-post fetch"): a notification tap into a post — from the in-app list or a push tap, both via the shared notification target resolver — resolves the FULL projection via `SinglePostApiClient.fetchFullPost` BEFORE `PostDetailRoute` is pushed (the screen itself does not call it), so the pushed route still carries every display field and the header renders from it exactly as on a card tap; a resolution failure pushes nothing.
- **Search-result entry** (owned by `mobile-search` § "A result tap opens PostDetailRoute hydrated from the by-id post read"): a search result tap resolves the FULL projection via `SinglePostApiClient.fetchFullPost` BEFORE `PostDetailRoute` is pushed (the screen itself does not call it), so the pushed route carries the real city, like state, reply count, and image, and the header renders from it exactly as on a card tap. Unlike the notification entry, a search hit already carries a renderable payload, so a resolution failure still pushes: the hit's own fields with the documented defaults (`cityName = ""`, `likedByViewer = false`, `replyCount = 0`, `imageUrl = null`). `distanceM` is `null` on both paths.

Replies cursor load-more is **no longer deferred** — it is implemented per the § "Replies list wires cursor load-more via PostDetailViewModel" requirement, which closes the replies half of GitHub issue [#188](https://github.com/aditrioka/nearyou-id/issues/188).

#### Scenario: A failed freshness read keeps the payload header

- **GIVEN** a `PostDetailScreen` composed from a `PostDetailRoute` whose resume-time `GET /api/v1/posts/{id}` returns `404` (or fails at transport)
- **WHEN** the screen renders
- **THEN** the header shows the route payload's content AND no error state is rendered

#### Scenario: A notification deep-link builds the route from the full projection

- **GIVEN** a `post`-target notification whose full-projection `GET /api/v1/posts/{target_id}` returns `200`
- **WHEN** the notification is tapped
- **THEN** the pushed `PostDetailRoute` carries the projection's display fields (content, city, like state, reply count, author identity) with `distanceM = null`, and the header renders from that payload

#### Scenario: Replies load-more is not deferred

- **WHEN** the replies list scrolls to its end while the last page carried a `next_cursor`
- **THEN** a `cursor=`-bearing follow-up `GET /replies` request is issued (the replies half of issue [#188](https://github.com/aditrioka/nearyou-id/issues/188) is implemented, not deferred)

#### Scenario: A search-result tap builds the route from the full projection

- **GIVEN** a search hit whose full-projection `GET /api/v1/posts/{post_id}` returns `200`
- **WHEN** the result is tapped
- **THEN** the pushed `PostDetailRoute` carries the projection's display fields (content, city, like state, reply count, author identity, image) with `distanceM = null`, and the header renders from that payload

#### Scenario: A failed search-result lookup still opens the detail from the hit

- **GIVEN** a search hit whose full-projection read returns `404` (or fails at transport)
- **WHEN** the result is tapped
- **THEN** a `PostDetailRoute` is pushed carrying the hit's content and author identity with `cityName = ""`, `likedByViewer = false`, `replyCount = 0`, `distanceM = null`, and no image
