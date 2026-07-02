## Context

Blocking is fully built except for two of its three spec'd entry points' completeness. The backend `user-blocking` capability exposes `POST/DELETE/GET /api/v1/blocks/*` with symmetric enforcement (mutual invisibility, follow auto-removal, DM block, 30/h rate limit). On mobile, the **profile-page** block shipped with `mobile-profile` (`ProfileViewModel.onBlockConfirmed` → `ProfileApiClient.block(userId)` → `BlockOutcome`), and the **Settings → blocked-users list + unblock** shipped under `data/block/BlockedUsersApiClient`. What is missing is the **post/reply context-menu** block that `docs/02` §"Block User" and `docs/03` §"Block User UX" both call for: *"Kebab menu (post, reply, profile page): 'Blokir @{username}'"*.

Today the post-detail post-header and reply-row overflow kebabs (added by `mobile-content-report`) host a single **"Laporkan"** item. The asymmetry between report and block is structural: a report targets a content id (`POST /api/v1/reports` with `target_id`), which the post-detail surface carries; a **block targets a user id** (`POST /api/v1/blocks/{userId}`), which the post-detail surface deliberately does **not** surface:
- `ReplyDto.authorId` (`@SerialName("author_id")`) is already on the wire but *never rendered* (the `mobile-post-detail` PII contract; `mobile-content-report` explicitly relied on this to *avoid* author-gating reply reports).
- `SinglePostResponse` deliberately omits the author UUID per issue #202 — yet it already derives `isAuthor` from `author_id` server-side, so the value exists; only its wire exposure is withheld.

## Goals / Non-Goals

**Goals:**
- Add "Blokir @{username}" to the post-detail **post-header** and **reply-row** kebabs, with the canonical confirmation dialog + success toast.
- One block-create implementation (`data/block/BlockSubmitter`) shared by profile and post-detail — no duplicated block path.
- Ship the full vertical slice: the additive `authorUserId` wire field (backend) + the mobile affordance, together.
- Reply cards render the author **display identity** (avatar + display name) per canonical mockup frame 7 — the additive `author_username` + `author_display_name` reply-wire fields (backend) + the card identity row (mobile), together (D7).

**Non-Goals:**
- No change to backend block semantics, the `user_blocks` schema, the rate limit, or the block-endpoint contract. **No Flyway migration.** (The `single-post-read` + reply wires gain **additive** fields — D1/D7 — with every pre-existing field and all visibility semantics unchanged.)
- No timeline-card (`PostCard`) block affordance (deferred — owned by in-flight #354).
- No post-detail header tap-to-profile navigation (deferred — separate concern).
- No like-per-reply and no premium tenure badge on reply rows (both visible in mockup frame 7 — separate capabilities; this change adds ONLY the identity row + block kebab).
- No new library/substrate (`gradle/libs.versions.toml` untouched) → no propose-time substrate WebSearch required.

## Cross-layer scope (docs/12)

| Layer | In this change | Notes |
|-------|----------------|-------|
| Backend (`:backend:ktor`) | YES | additive `authorUserId` on `single-post-read` wire DTO + projection; additive `author_username` + `author_display_name` on the reply list/create wire (D7); no migration, no new endpoint, block endpoint reused as-is |
| Mobile (`:mobile:app`) | YES | post-header + reply-row block affordances; shared `BlockSubmitter`; confirmation dialog; reply-card identity row (mockup frame 7); `authorUserId` sourced from the single-post freshness read (NOT the serialized back stack) |
| Admin (`:backend:ktor/admin`) | NONE | block has no admin surface in this change; the read-only `admin-block-registry` already shipped |

Deferred layers are captured as explicit `specs/**` deferred requirements (positive statement + negative-guard scenario + tracking issue), not bare prose — see the new capability spec.

## Standards conformance (docs/11 Pattern Registry)

This change builds entirely on already-registered patterns and introduces **no new pattern** (no docs/11 § Pattern Registry amendment required):
- **State management (§2.2):** the existing `PostDetailViewModel` (one `stateIn` `uiState`; one-shot events as nullable UiState fields cleared via `onXxxShown()`) is extended — block confirm-request, success toast, rate-limit message, and navigate-back/row-removal are modeled as UiState fields, not event streams. Mirrors the shipped `ProfileViewModel.onBlockConfirmed`.
- **Data layer (§2.6 + §4 naming coherence):** `data/block/BlockSubmitter` mirrors the shipped `data/report/ReportSubmitter` shared-seam pattern (one submission implementation, dependency direction UI → ViewModel → Submitter → ApiClient). `BlockSubmitter`/`BlockOutcome` follow the `ReportSubmitter`/`ReportOutcome` naming.
- **UI (§2.8):** `ui/components/BlockConfirmDialog` mirrors `ui/components/ReportDialog` (shared M3 `AlertDialog`); the kebab item mirrors the shipped `PostReportMenu` `DropdownMenuItem`. No new visual primitive — the report kebab + profile block dialog are the canonical visual reference.
- **Backend layering (§3.1):** the additive `authorUserId` flows through the existing `single-post-read` route → projection; no new layer.
- **Strings (invariant):** all new copy via Compose Multiplatform `Res.string.*`.

## Decisions

### D1 — Expose `authorUserId` on the single-post-read wire (never rendered, block-action-only)
The post-header block needs the author UUID. **Chosen:** add `authorUserId: String` to `SinglePostResponse` (and its select projection — the value already exists server-side, used for `isAuthor`). The post-detail surface obtains it from its **existing single-post freshness read** (`SinglePostApiClient.fetchFullPost` → `SinglePostResponse`, the same read that already yields `isAuthor` for the edit/report gate) — **NOT** from the `PostDetailRoute` payload, which continues to carry no author UUID (the serialized-back-stack stays UUID-free; `PostDetailRoute`'s "MUST NOT declare the author UUID" requirement is preserved unmodified). The value is **never rendered** in any UI string, used only as the `POST /api/v1/blocks/{userId}` path param. When the freshness read degrades to `Unavailable`, no `authorUserId` resolves and the block affordance is simply absent (the same dependence the edit affordance already has).
- *Why over alternatives:* (a) a block-by-username endpoint — doesn't exist; would add a new backend contract + a username→uuid resolution race. (b) navigate-to-profile-first then block — the post-detail header is not a tap target (out of scope here) and would still need the UUID to build `ProfileRoute`. (c) keep the UUID off the wire and defer post-block — leaves the *primary* target (the post author) unblockable while a reply author is blockable, an incoherent half-feature.
- *Precedent:* identical to the shipped `ReplyDto.authorId` (UUID on wire, never rendered, action-only). The exposure is a deliberate, scoped **relaxation of issue #202's** "no author UUID on the single-post wire" stance — recorded by amending the `SinglePostResponse` KDoc and reconciled against #202 (proposal Impact / B.3). A bare UUID carries no coordinate or display identity; `isAuthor` is already derived from it.

### D2 — Shared `BlockSubmitter` seam, profile refactored onto it
**Chosen:** extract `data/block/BlockSubmitter` (wraps `POST /api/v1/blocks/{userId}` → `BlockOutcome`) and route both profile and post-detail through it; refactor `ProfileApiClient.block`/`ProfileRepository` onto the shared seam (behavior-preserving). One block-create implementation, exactly as `mobile-content-report` did for reporting (`ReportSubmitter` serves profile + post-detail + chat). Rejected: duplicating the profile block call into post-detail (two implementations = the patchwork failure mode §4 forbids).

### D3 — Confirmation dialog as a shared `ui/components` composable with canonical copy
**Chosen:** `BlockConfirmDialog` (M3 `AlertDialog`, mirrors `ReportDialog`) rendering the verbatim `docs/03` copy — body *"Blokir @{username}? Kalian berdua tidak akan saling melihat post, profil, atau bisa memulai percakapan baru."*, a red destructive **"Blokir"** confirm, a secondary **"Batal"**. Username interpolated via a parameterized `Res.string`. Reused by both kebabs.

### D4 — Block-success outcomes mirror the shipped profile treatment
- **Post block** success → toast "Pengguna telah diblokir" + **pop back** to the timeline (the just-blocked post 404s on any re-read — exactly `ProfileViewModel`'s navigate-back rationale).
- **Reply block** success → toast + **remove the reply row** from the current list (the reply hides bidirectionally; a local removal keeps the open post visible, no full-screen pop).
- **429** → typed rate-limit message ("Terlalu banyak aksi blokir. Coba lagi nanti."), **no** nav. **NetworkError** → generic action-failed message. Mapping is identical to `ProfileMessage.BLOCK_RATE_LIMITED` / `ACTION_FAILED`.

### D5 — Self-block guarding
- **Post:** the block item renders only when `!isAuthor` (the server-derived flag already gating the edit affordance) — you never see "Blokir" on your own post.
- **Reply:** guard via the existing `SelfUserIdProvider` (compare `ReplyDto.authorId` to the session uid); hide the block item on your own reply. The backend also rejects self-block (`400 cannot_block_self`) as belt-and-suspenders.
- **Intentional divergence from the report affordance:** the shipped reply *report* affordance deliberately does NOT gate on authorship and never uses `author_id` (`mobile-content-report` — a self-report is harmless). The reply *block* affordance DOES read `author_id` for the self-block gate (a self-block is a worse UX) and as the block target. This is a deliberate, scoped asymmetry — the field is already on the reply wire and stays never-rendered/never-logged; the only new use is a client-side comparison + the outbound path param, leaking nothing. It requires MODIFYing the `mobile-post-detail` "Pure PostDetailUiState projection (PII-free)" requirement (which today forbids `author_id` in any projected state) to carve out this never-rendered, gate/path-only reply `author_id`.

### D6 — Deferred entry points as explicit requirements
Two entry points are deferred, handled differently by deferral type:
- **Timeline-card (`PostCard`) block kebab** — a deferred *layer of this capability*, so it is captured as an **explicit deferred requirement** in the new capability spec (positive statement + a negative-guard scenario asserting `PostCard`/`mobile-post-card` are untouched) + a tracking `follow-up` issue — mirroring how `mobile-content-report` deferred the timeline-card report kebab as #363. PostCard is owned by in-flight `image-attached-posts` #354; touching it here would create a merge conflict and violate the footprint-disjoint heuristic.
- **Post-detail header tap-to-profile** — NOT a layer of the block capability (it is a navigation affordance that would unlock the *whole* profile surface, a separate concern), so it is tracked only as a `follow-up` issue (task 6.2), not a deferred requirement of this spec.

### D7 — Reply author identity on the wire + card (operator-approved deviation, 2026-07-03)
The canonical "Blokir @{username}" copy is unrenderable on reply kebabs as originally proposed: the reply wire carries only the `author_id` UUID (never rendered), so there is no username to interpolate — an internal contradiction between this change's reply-block requirement and the shipped `mobile-post-detail` "reply cards render no author identity" contract, discovered at implementation time. **Chosen (operator decision):** the backend supplies the identity — additive **`author_username` + `author_display_name`** on the reply list AND create (201) wire, and reply cards render the author display identity (avatar + display name, the same `LetterAvatar`/name treatments as the post header). This conforms to the canonical mockup **frame 7 · "Detail postingan + balasan"**, which has always shown reply author identity ("Sinta Maharani · 25 mnt", …) — the shipped anonymous rendering was a wire-era gap, not product intent; `docs/03` §Block User UX likewise assumes reply kebabs can name their author.
- *Projection mechanics:* the list query already `LEFT JOIN visible_users vu` for the shadow-ban predicate; identity is projected via a raw-`users` join on the already-visibility-resolved `pr.author_id` (required anyway for the caller's OWN shadow-banned replies, which are absent from `visible_users` — the same self-arm rationale as `resolveVisiblePost`). The INSERT-RETURNING path projects the caller's own identity the same way. Every returned row has already passed the visibility predicates, so the raw-`users` identity read leaks nothing.
- *Rejected:* (a) a username-free reply dialog copy ("Blokir penulis balasan") — deviates from the canonical docs/03 copy AND cements the mockup-non-conformant anonymous reply card; (b) fetching the username per reply via `GET /api/v1/users/{author_id}` on dialog-open — N round-trips, a loading/failure state inside a confirmation dialog, and the kebab label still can't name the author.
- *Compatibility:* mobile `ReplyDto` declares the new fields nullable-with-default, so a stale backend (e.g. staging mid-rollout) still decodes; a null identity omits the card identity row gracefully (the post header's legacy-payload precedent).

## Risks / Trade-offs

- **[`authorUserId` wire exposure read as a PII regression]** → never rendered, block-action-only, byte-for-byte the `ReplyDto.authorId` precedent already in production; KDoc + #202 reconciliation make the relaxation explicit and reviewable. The field leaks nothing the existing `isAuthor` boolean doesn't already encode the existence of.
- **[Footprint overlap with chat-embedded-posts #423 on single-post-read]** → verified: #423 does not touch `SinglePostRoutes`. No migration → no Flyway V-number collision with any in-flight claim.
- **[Reply-row removal vs. server truth divergence]** → local-remove is an optimistic UI affordance on a confirmed 204; the next post-detail (re)fetch reconciles from `visible_*` views (the blocked reply is already excluded server-side).
- **[Shared-seam refactor regresses profile block]** → the profile block path is refactored behavior-preservingly onto `BlockSubmitter`; a regression scenario asserts the existing profile block tests pass unchanged (mirrors the `mobile-content-report` "report behavior unchanged" guard).
- **[Reply identity exposure read as a privacy regression]** → `username`/`display_name` are the same public display identity every post card already renders; the canonical mockup frame 7 has always shown it on replies. The UUID (`author_id`) posture is unchanged (never rendered/logged). Shadow-ban/block visibility is unaffected: identity is projected only for rows the caller could already see.

## Migration Plan

No DB migration. The `authorUserId` field and the reply-wire `author_username` + `author_display_name` fields are additive and backward-compatible: older clients ignore the unknown keys (`ignoreUnknownKeys`); new clients declare the reply identity fields nullable-with-default so an older backend still decodes. Mobile ships on the normal flavor cadence. **Rollback:** revert the PR — the additive fields have no persisted state and no consumer outside this change.

## Open Questions

None blocking. (Considered and resolved inline: post-block uses pop-back rather than an in-place "post unavailable" state, matching the shipped profile navigate-back treatment.)
