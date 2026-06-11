# Tasks: mobile-timeline-card-redesign

## 1. Backend — author identity on the three timeline responses

- [x] 1.1 Add `JOIN visible_users u ON u.id = p.author_id` + `u.username AS author_username, u.display_name AS author_display_name` projections to the Nearby SQL in `infra/supabase/src/main/kotlin/id/nearyou/app/infra/repo/JdbcPostsTimelineRepository.kt` and extend its row type
- [x] 1.2 Same join + projections + row type in `JdbcPostsFollowingRepository.kt`
- [x] 1.3 Same join + projections + row type in `JdbcPostsGlobalRepository.kt`
- [x] 1.4 Thread the two fields through the three services' row models (`NearbyTimelineService` / `FollowingTimelineService` / `GlobalTimelineService`)
- [x] 1.5 Add `authorUsername: String` + `authorDisplayName: String` (bare camelCase, NO `@SerialName`) to `NearbyPostDto` / `FollowingPostDto` / `GlobalPostDto` in `TimelineRoutes.kt` + the three route mappings
- [x] 1.6 Extend `NearbyTimelineServiceTest` (tagged `database`): identity values match the author's users row; author-identity JOIN does not alter row count (35-post scenario); fixtures seed ≥2 posts by ONE author and assert identity on both; existing scenarios stay green
- [x] 1.7 Extend `FollowingTimelineServiceTest` + `GlobalTimelineServiceTest` with the same identity scenarios each (values-match + row-count + same-author-two-posts)
- [x] 1.8 Extend the route-level wire tests: `authorUsername`/`authorDisplayName` keys present with exact camelCase (assert NO `author_username` snake variant) on all three endpoints
- [x] 1.9 Amend `docs/05-Implementation.md` § Timeline Implementation — add the join + two SELECT columns to all three canonical SQL blocks (keep the "mirrors `Jdbc*Repository`" note accurate)

## 2. Mobile — shared post card in ui/components

- [x] 2.1 Add `:shared:resources` strings for the card + app bar: handle format (`post_card_handle` = "@%s"-style), any meta separator, `app_name` reuse check for the logo `contentDescription` — no hardcoded UI literals
- [x] 2.2 Create `ui/components/PostCard.kt` (new package, docs/11 §2.1 first occupant): identity header row (letter avatar, display name, handle, time label), content, location meta row (coral `locationPin` pin + city + optional `DistanceRenderer` distance; row omitted when city empty AND distance null), read-only counts row (like state icon + reply icon + count; no click semantics), whole-card `onOpen` tap only
- [x] 2.3 Create the pure letter-avatar derivation in commonMain (initials: first code point of first + last word, uppercased; deterministic `authorUsername` → {primary,secondary,tertiary}Container token-pair mapping) as testable non-composable functions + the avatar composable
- [x] 2.4 commonTest: initials derivation ("Budi Santoso"→"BS", "Raka"→"R", surrogate-pair-leading name no-crash, blank `""`/whitespace-only → empty initials no-crash, `" Budi  Santoso"` double-space → "BS"), deterministic color mapping (same username → same pair)
- [x] 2.5 Robolectric `PostCardTest` (androidUnitTest, added to the Release-variant exclude): identity nodes rendered, no-UUID/no-coordinate assertion, liked-state icon switch, the ONLY clickable node is the card itself (counts row + avatar/name expose no click action; avatar-region tap fires whole-card `onOpen` exactly once), `distanceM` present → DistanceRenderer string shown vs `distanceM = null` → NO distance string, empty-city+null-distance hides the meta row, NO clock-icon node (time is text in the identity header), maximal-length identity (50/60 chars) single-line ellipsized with time label still visible, light+dark render

## 3. Mobile — consume the card in Nearby + Global feeds

- [x] 3.1 Add `authorUsername` + `authorDisplayName` (required `String`, bare camelCase) to `NearbyPostDto` + domain `NearbyTimelinePost` mapping; update MockEngine fixtures to the shipped wire keys
- [x] 3.2 Same DTO + domain + fixture updates for the Global feed (`GlobalPostDto` / its domain model)
- [x] 3.3 Replace `NearbyPostCard` usage with the shared `PostCard` in `NearbyTimelineScreen.kt` and DELETE the local composable; pass `distanceM` through; extend `onOpenPost` payload with the two identity fields
- [x] 3.4 Replace `GlobalPostCard` usage with the shared `PostCard` in `GlobalTimelineScreen.kt` and DELETE the local composable; `distanceM = null`; extend `onOpenPost` payload with the two identity fields
- [x] 3.5 Update Nearby + Global DTO parse tests (shipped mixed-case wire incl. the two new keys; snake_case-guard fixture extended with `author_username`/`author_display_name` non-population)
- [x] 3.6 Update `NearbyTimelineScreenTest` + `GlobalTimelineScreenTest`: display identity rendered, UUID/coords still absent, `onOpenPost` payload carries the identity fields, DistanceRenderer assertion still at rendered-card level (Nearby)

## 4. Mobile — post-detail identity + route payload

- [x] 4.1 Add `authorUsername: String = ""` + `authorDisplayName: String = ""` to `PostDetailRoute` in `NavKeys.kt` (stays registered in the polymorphic `SerializersModule`; no lat/lng, no UUID)
- [x] 4.2 Construct the route with the identity fields at the `appEntryProvider` / `AppShellScreen` call-site wiring (`onOpenPost` → `backStack.add(PostDetailRoute(...))`)
- [x] 4.3 Render the identity row in the `PostDetailScreen` header from the payload, reusing the shared avatar/identity sub-components from `ui/components` (NOT the whole card); omit the row gracefully when the payload identity is empty
- [x] 4.4 Tests: route round-trip with the new fields; decode-missing-fields → defaults `""` (restore-compat scenario); header renders identity without any new network request; empty-identity payload renders no "@" orphan; no-UUID/no-coordinate assertion stays green
- [x] 4.5 Update `HomeScreen` / `AppShellScreen` / `appEntryProvider` tests for the widened `onOpenPost` payload (root-stack push carries identity, never lat/lng)

## 5. Mobile — shell app bar with brand logo

- [x] 5.1 Add the pinned `CenterAlignedTopAppBar` to `AppShellScreen`'s Scaffold `topBar` slot, Home section only: centered `logo_brand_light`/`logo_brand_dark` per active scheme, `contentDescription = stringResource(Res.string.app_name)`
- [x] 5.2 Shell tests: Home shows the logo app bar (contentDescription match), light vs dark asset selection, Notifikasi/Profil sections render no shell top app bar, insets still applied once (no double status-bar gap — flush-under-app-bar check)

## 6. Docs amendments (same PR — canonical-docs reconciliation)

- [x] 6.1 Amend `docs/05-Implementation.md` § Timeline Implementation (covered by 1.9 — verify all three blocks + the "mirrors" notes after the backend lands)
- [ ] 6.2 Amend `docs/03-UX-Design.md` § canonical glyph list (≈line 316): remove **time (clock)** from the post-card glyphs (time renders as text in the identity header per mockup frames 1/19) and note the shell-owned centered brand-logo app bar in the § inset paragraph (≈line 312)

## 7. Verification gates

- [ ] 7.1 Backend gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green locally
- [ ] 7.2 Mobile gate: `./gradlew :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest` green (new ScreenTests in the Release exclude) + `:mobile:app:iosSimulatorArm64Test` for commonTest additions
- [ ] 7.3 Manual verification per docs/11 §5 DoD #3 (`verify-loop`): Android emulator AND iOS simulator — screenshot Nearby + Global feeds light/dark vs mockup frames 1/19, post-detail header identity, app bar logo; attach evidence to the PR body; `mobile-ui-foundation` checklist pass
- [ ] 7.4 Staging branch deploy + smoke per docs/11 §5 DoD #4 (runtime-impacting backend change): authenticated `GET /api/v1/timeline/nearby|global|following` on staging each return `authorUsername`/`authorDisplayName` on every post (mint via `dev/scripts/mint-staging-jwt.sh`)
- [ ] 7.5 After merge: tick the "post card" half of audit item 05-#11 in `dev/audits/2026-06-10-holistic-audit/PROGRESS.md` § Remaining — name the residuals explicitly: the list-state kit half AND the `PostDetailScreen` `PostHeader` copy (this change reuses only the avatar/identity sub-components there; full header unification stays open) — archive-phase task
