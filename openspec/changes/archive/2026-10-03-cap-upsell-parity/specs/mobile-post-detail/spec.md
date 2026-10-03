## MODIFIED Requirements

### Requirement: Like toggle is optimistic and status-driven, with count and cap upsell

The like control's initial state SHALL come from the `likedByViewer` route payload. Activating it SHALL flip the state **optimistically** and call `POST /api/v1/posts/{post_id}/like` (when liking) or `DELETE /api/v1/posts/{post_id}/like` (when unliking) via the shipped `HttpClient` (Bearer + 401 refresh owned by the `Auth` plugin; MUST NOT be reimplemented; NO `X-Session-Id` header — the like endpoints are not session-soft-capped). Both verbs return `204 No Content` on the happy path (`DELETE` is a pure no-op that NEVER returns 404).

The repository SHALL map results to a sealed `LikeOutcome`:
- `204` → `Liked` / `Unliked`;
- `429` → `RateLimited(retryAfterSeconds)`;
- `404` → `PostGone`;
- `5xx`/network-IO → `NetworkError`.

On any non-`204`/network failure the optimistic flip SHALL be reverted.

A `RateLimited` like SHALL surface the Free like-cap upsell as the shared `DailyCapUpsellDialog` (`mobile-cap-upsell-dialog`, mockup frame 18) — NOT an inline banner. The body is `stringResource(Res.string.post_detail_likes_cap_upsell)` (`docs/03-UX-Design.md` § Rate Limit Communication), formatted with the dialog's live per-minute countdown derived from `retryAfterSeconds`. The dialog's CTAs:
- "Tutup" / scrim / back / the countdown auto-dismiss SHALL clear the rate-limited state.
- "Aktifkan Premium" SHALL clear it AND invoke the screen's hoisted `onActivatePremium(PaywallEntry.LIKE_CAP)`, which `appEntryProvider` wires to push `PaywallRoute(LIKE_CAP)` onto the root back stack. The screen stays navigation-free.

The numeric like count SHALL be fetched via `GET /api/v1/posts/{post_id}/likes/count` (`{ "count": <Long> }`) and displayed via `stringResource(Res.string.post_detail_like_count)` when available; a count-fetch failure SHALL degrade gracefully (hide the count, keep the toggle functional).

#### Scenario: Optimistic like issues POST and reflects the liked state

- **GIVEN** a `FakePostDetailFlow` whose `toggleLike(currentlyLiked = false)` returns `LikeOutcome.Liked` AND the route payload has `likedByViewer = false`
- **WHEN** the like control is activated
- **THEN** the control immediately reflects the liked state AND `toggleLike` was invoked

#### Scenario: A 429 reverts the optimistic flip and shows the cap dialog

- **GIVEN** the like control is in the not-liked state AND `toggleLike` returns `LikeOutcome.RateLimited(retryAfterSeconds = 3600)`
- **WHEN** the like control is activated
- **THEN** the optimistic flip is reverted to not-liked AND the `DailyCapUpsellDialog` is shown whose body matches `stringResource(Res.string.post_detail_likes_cap_upsell)` formatted with the countdown ("1 j 0 mnt") AND no inline cap banner is rendered

#### Scenario: The like cap CTA opens the paywall as LIKE_CAP

- **GIVEN** the like cap dialog shown on post-detail under the real `appEntryProvider`
- **WHEN** "Aktifkan Premium" is tapped
- **THEN** the dialog is dismissed AND the root back stack's top entry is `PaywallRoute(entry = PaywallEntry.LIKE_CAP)`

#### Scenario: DELETE unlike maps to Unliked

- **GIVEN** a `MockEngine` returning `204` for `DELETE /api/v1/posts/{post_id}/like`
- **WHEN** the repository processes an unlike
- **THEN** the outcome is `LikeOutcome.Unliked` AND no crash occurs (DELETE never yields 404)

#### Scenario: Like count is fetched and shown, degrading on failure

- **GIVEN** a `MockEngine` returning `200 { "count": 42 }` for `GET /api/v1/posts/{post_id}/likes/count`
- **WHEN** the screen loads
- **THEN** the rendered tree contains a node whose text matches `stringResource(Res.string.post_detail_like_count)` formatted with `42`; AND given a count fetch that fails, the screen renders no count node and the like toggle remains functional

### Requirement: Reply composer posts with a 280-code-point guard, local append, and cap upsell

The reply composer SHALL render a multiline field (placeholder `stringResource(Res.string.post_detail_reply_placeholder)`), a live `N/280` counter via `stringResource(Res.string.post_detail_reply_counter)` computed in **Unicode code points** (NOT UTF-16 units), and a "Balas" CTA via `stringResource(Res.string.cta_reply)` that is disabled while content is empty / over-limit / in-flight.

**Empty-vs-over-limit is a CLIENT-side concern.** The pre-submit code-point projection disables the CTA, so the client never submits empty or >280 content. There is no server round-trip to distinguish "empty" from "too long", and the backend would not provide one (see below).

Submitting SHALL call `POST /api/v1/posts/{post_id}/replies` with the body `{ "content": "<text>" }`. The request DTO declares `content: String` (non-null), **deliberately tightening** the shipped `ReplyCreateRequest(content: String? = null)`. The client always sends a non-null `content`; the wire's nullable+default is backend input-leniency, irrelevant to an outbound-only request DTO.

The repository SHALL map results to a sealed `ReplyPostOutcome`:
- `201` → `Success(reply)`. The returned `ReplyDto` is appended to the in-memory list AND the displayed reply count is incremented; the list is NOT re-fetched.
- `429` → `RateLimited(retryAfterSeconds)`.
- `400 invalid_request` → a single `InvalidContent` outcome. The SHIPPED backend emits the **same** `invalid_request` code for both empty and >280 content (`backend/ktor/.../engagement/ReplyRoutes.kt` `respondInvalidRequest`, message-only difference), so the client MUST NOT assume a server empty-vs-too-long distinction. This is a defensive edge given the client guard: it maps to a retryable banner with a logged diagnostic, NOT a crash, NOT a silent no-op.
- `404` → `PostGone`.
- `5xx`/network-IO → `NetworkError`.

Bearer + 401 refresh are owned by the shipped `Auth` plugin.

A `RateLimited` reply SHALL surface the Free reply-cap upsell as the shared `DailyCapUpsellDialog` (`mobile-cap-upsell-dialog`, frame 18) — NOT an inline banner. The body is `stringResource(Res.string.post_detail_reply_cap_upsell)`, formatted with the live countdown. The composer text SHALL be preserved, so the user can send it after the reset or after upgrading. The dialog's CTAs:
- "Tutup" / scrim / back / auto-dismiss SHALL clear the rate-limited state.
- "Aktifkan Premium" SHALL clear it AND invoke `onActivatePremium(PaywallEntry.REPLY_CAP)`, which `appEntryProvider` wires to push `PaywallRoute(REPLY_CAP)`.

Replies can only be written on post-detail, so this is the Free reply cap's sole paywall path.

#### Scenario: 280 code points enabled, 281 over-limit and disabled

- **WHEN** the composer projection is invoked with a 280-code-point string and again with a 281-code-point string (not in-flight)
- **THEN** the 280 case enables the "Balas" CTA with over-limit false AND the 281 case disables it with over-limit true

#### Scenario: Multi-byte emoji counts as one code point

- **GIVEN** a string of 280 non-BMP emoji (one code point, two UTF-16 units each)
- **WHEN** the counter computes the count
- **THEN** the count is 280 (NOT 560) AND the CTA is enabled

#### Scenario: 201 appends the new reply locally and bumps the count without re-fetch

- **GIVEN** a `FakePostDetailFlow` whose `postReply(...)` returns `ReplyPostOutcome.Success` with a new `ReplyDto` AND a displayed reply count of `2`
- **WHEN** a reply is submitted successfully
- **THEN** the new reply appears in the list AND the displayed reply count becomes `3` AND no `GET /api/v1/posts/{post_id}/replies` re-fetch is issued

#### Scenario: 429 on reply shows the reply-cap dialog and keeps the draft

- **WHEN** a reply "halo" is submitted and `postReply(...)` returns `ReplyPostOutcome.RateLimited(retryAfterSeconds = 3600)`
- **THEN** the `DailyCapUpsellDialog` is shown whose body matches `stringResource(Res.string.post_detail_reply_cap_upsell)` formatted with the countdown AND no inline cap banner is rendered AND the composer field still contains "halo"

#### Scenario: The reply cap CTA opens the paywall as REPLY_CAP

- **GIVEN** the reply cap dialog shown on post-detail under the real `appEntryProvider`
- **WHEN** "Aktifkan Premium" is tapped
- **THEN** the dialog is dismissed AND the root back stack's top entry is `PaywallRoute(entry = PaywallEntry.REPLY_CAP)`

#### Scenario: 400 invalid_request maps to a single InvalidContent outcome (no server empty/too-long split)

- **GIVEN** a `MockEngine` returning `400 {"error":{"code":"invalid_request"}}` (the single code the backend emits for both empty and over-limit content)
- **WHEN** the repository processes the response
- **THEN** the outcome is the single `InvalidContent` (retryable, with a logged diagnostic) — NOT two distinct `ContentEmpty`/`ContentTooLong` server outcomes, and NOT a generic wildcard failure

#### Scenario: Empty/over-limit is gated client-side before any POST

- **GIVEN** the composer projection with empty content, and again with a 281-code-point string
- **THEN** in both cases the "Balas" CTA is disabled (the over-limit flag set for the 281 case) so no `POST /api/v1/posts/{post_id}/replies` is issued — the empty-vs-too-long UX is driven by the client projection, not a server response
