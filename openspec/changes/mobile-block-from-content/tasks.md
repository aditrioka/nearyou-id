## 1. Backend: expose authorUserId on single-post-read (additive, no migration)

- [ ] 1.1 Add `authorUserId: String` to `SinglePostResponse` (`backend/ktor/.../post/SinglePostRoutes.kt`) and include the author UUID in the select projection (the column already backs the existing `isAuthor` derivation — no new join, no migration). Amend the DTO KDoc to record the relaxed issue-#202 stance (carries `authorUserId` at timeline-wire parity, never rendered; coordinates still excluded).
- [ ] 1.2 Backend test (`SinglePostRoutesTest` or the single-post-read suite, DB-tagged `!network`): assert the `200` response carries `authorUserId` equal to the post author's UUID AND that `isAuthor` and every pre-existing field are unchanged (additive). Keep the `!network` tag + autoClose discipline for any new pool.

## 2. Mobile data layer: shared block-create seam

- [ ] 2.1 Add `authorUserId` to the mobile single-post model (`SinglePostFullDto` in `post/SinglePostApiClient.kt`) and thread it through the freshness refresh outcome (`PostRefreshOutcome.Loaded` / `PostEditRepository`) alongside the existing `isAuthor`.
- [ ] 2.2 Create `data/block/BlockSubmitter` (+ `BlockOutcome` sealed type: `Blocked` / `RateLimited(retryAfterSeconds)` / `NetworkError`) wrapping `POST /api/v1/blocks/{userId}` on the shared `HttpClient`, mirroring `data/report/ReportSubmitter`. Register it in `MobileModule` (Koin singleton).
- [ ] 2.3 Refactor the profile block path (`ProfileApiClient.block` / `ProfileRepository` / `ProfileViewModel.onBlockConfirmed`) onto `BlockSubmitter` behavior-preservingly (one block-create implementation). Keep the existing `BlockOutcome` mapping in `profile/ProfileFlow.kt` aligned with the shared type (or consolidate to the shared one).
- [ ] 2.4 `BlockSubmitter` unit test (commonTest): 204 → `Blocked`, 429 (with `Retry-After`) → `RateLimited`, transport/other failure → `NetworkError`.

## 3. Mobile UI: shared confirmation dialog + strings

- [ ] 3.1 Add `Res.string.*` entries: the block menu item label ("Blokir @{username}" — parameterized), the dialog body ("Blokir @{username}? Kalian berdua tidak akan saling melihat post, profil, atau bisa memulai percakapan baru." — parameterized), confirm "Blokir", dismiss "Batal", success toast "Pengguna telah diblokir", rate-limit message ("Terlalu banyak aksi blokir. Coba lagi nanti."), and the generic action-failed message (reuse the existing one if present). Add the matching per-key imports.
- [ ] 3.2 Create `ui/components/BlockConfirmDialog` (M3 `AlertDialog`, mirroring `ui/components/ReportDialog`): canonical copy, destructive/error-colored "Blokir" confirm + secondary "Batal", username interpolated. No hardcoded strings.

## 4. Mobile post-detail: post-header + reply-row block affordances

- [ ] 4.1 Post header: add a "Blokir @{username}" item to the post-header overflow kebab (alongside "Laporkan"), shown only when `!isAuthor` AND the freshness read resolved an `authorUserId`. Wire confirm → `BlockSubmitter` against `authorUserId` via `PostDetailViewModel`; model the confirm-request + outcomes (toast / rate-limit / action-failed + pop-back) as nullable `PostDetailUiState` fields cleared via `onXxxShown()`.
- [ ] 4.2 Reply row: add a "Blokir @{username}" item to the reply-row overflow kebab (alongside "Laporkan"), shown only when the reply's `author_id` (already on the wire) is NOT the session user (`SelfUserIdProvider`). Wire confirm → `BlockSubmitter` against the reply `author_id`; on `Blocked`, remove the reply row locally + surface the toast. Never render `author_id`.
- [ ] 4.3 Block outcomes: post block `Blocked` → toast + pop `PostDetailScreen` off the root back stack; reply block `Blocked` → toast + local row removal (no pop); `RateLimited`/`NetworkError` → message, no nav/no removal.

## 5. Mobile tests (post-detail + regression)

- [ ] 5.1 `PostDetailScreenTest` (Robolectric, Release-excluded) — post-header block: affordance present on `!isAuthor`, absent on own post, AND **absent when the freshness read resolved no `authorUserId`** (Unavailable); confirm → `POST /api/v1/blocks/{uuid}` against the correct author UUID; post block pop-back; author UUID never in the rendered tree.
- [ ] 5.2 `PostDetailScreenTest` — reply-row block: affordance present on another user's reply, absent on the viewer's own reply (`SelfUserIdProvider` gate); confirm → `POST /api/v1/blocks/{replyAuthorId}`; reply block removes the row (no pop); reply `author_id` never in the rendered tree.
- [ ] 5.3 Block dialog + outcome-mapping tests: dialog renders the canonical copy for `@{username}`; "Batal" issues no block; `Blocked` → success toast; `RateLimited` → rate-limit message + no nav/no removal; `NetworkError` → action-failed + no nav/no removal; `HttpClientFactory` logging stays `LogLevel.HEADERS`; a `BlockConfirmDialog`/block-menu source scan asserts no hardcoded UI strings.
- [ ] 5.4 Regression: run the existing profile block tests after the shared-seam refactor — assert unchanged (same `Blocked`/`RateLimited`/`NetworkError` mapping + navigate-back). Add a source/structural guard that exactly ONE `BlockSubmitter` block-create implementation exists (no second `POST /api/v1/blocks/{userId}` call site).
- [ ] 5.5 Negative-guard test: `PostCard` / `mobile-post-card` are NOT modified and carry no block affordance (the timeline-card deferral guard).

## 6. Deferral tracking + docs reconciliation

- [ ] 6.1 File a `follow-up` GitHub issue for the deferred **timeline-card block kebab** (the MODIFY hook for a future change, mirroring report #363); record its number in the `mobile-block-from-content` spec's "Timeline-card block entry point is deferred" requirement.
- [ ] 6.2 File a `follow-up` GitHub issue for the deferred **post-detail header tap-to-profile** (separate concern).
- [ ] 6.3 Confirm GitHub issue [#200](https://github.com/aditrioka/nearyou-id/issues/200) (block-from-post-context deferral) is resolved by this change; close it on archive.
- [ ] 6.4 Docs reconciliation: add a one-line note to `docs/05-Implementation.md` §"User Blocking" (and/or the issue-#202 reference) that `single-post-read` now carries `authorUserId` at timeline-wire parity for the block action (never rendered). Surface to the operator if a broader #202 amendment is wanted.

## 7. Verify + gate

- [ ] 7.1 Run the pre-push gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` (both lint frameworks) + the mobile unit suite `:mobile:app:testDevDebugUnitTest`.
- [ ] 7.2 Device/emulator verify (per `verify-loop` / `mobile-ui-foundation`): open a post detail authored by another user → "Blokir" in the post kebab + a reply kebab → confirm dialog (canonical copy) → block → toast + pop-back / row-removal; confirm "Blokir" is absent on own post/reply. Capture evidence for the PR (docs/11 §5 DoD).
