# Tasks: mobile-inline-post-actions

## 1. Like seam extraction (data layer)

- [x] 1.1 Create `data/like/LikeFlow.kt` (target-shape package): `interface LikeFlow { suspend fun toggleLike(postId: String, currentlyLiked: Boolean): LikeOutcome }` — `LikeOutcome` import from its existing `id.nearyou.app.post` location (no file moves)
- [x] 1.2 Make `PostDetailFlow : LikeFlow` (its `toggleLike` member moves to the super-interface; same signature/semantics); confirm `PostDetailRepository` compiles unchanged
- [x] 1.3 Add `single<LikeFlow> { get<PostDetailRepository>() }` to `mobileModule` (alongside the existing `single<PostDetailFlow>` — same singleton)
- [x] 1.4 Tests: Koin-graph inspection (both seams resolve to the same `PostDetailRepository` instance) + existing post-detail tests stay green (`FakePostDetailFlow` now implements `LikeFlow` transitively)

## 2. Shared inline-like controller (ui/timeline)

- [x] 2.1 Create the shared, Compose-free inline-like controller in target-shape `ui/timeline/`: optimistic flip → `LikeFlow.toggleLike` → outcome application (keep / revert+cap / revert+reload / revert), per-post in-flight set, nullable one-shot cap state (`retryAfterSeconds`) with an explicit clear — generic over the two feed post types (copy-lambda or small interface; apply-time choice per design D2)
- [x] 2.2 commonTest for the controller: optimistic flip in BOTH directions (incl. `currentlyLiked = true` → unlike — the direction argument is asserted, never hardcoded); `Liked`/`Unliked` keep; `RateLimited` revert + cap state set + clear callback; `PostGone` revert + reload trigger; `NetworkError` silent revert; in-flight re-tap ignored (suspending fake); interleaved-refresh scenario (outcome swap during in-flight toggle: applies against the fresh list, no-op when the post is gone, cap state still raised on `RateLimited`)

## 3. PostCard action row

- [x] 3.1 Rework the counts row in `ui/components/PostCard.kt` into the action row: reply affordance (icon + `replyCount`, one tappable unit → hoisted `onReplyShortcut`), like affordance (icon only, filled+`locationPin` / outlined+muted, keep existing test tags → hoisted `onToggleLike`); ≥48dp touch targets, ripple, NO send affordance, NO like count
- [x] 3.2 Add `stringResource` content descriptions for both affordances (new keys in `:shared:resources` strings.xml); keep zero hardcoded literals in `PostCard.kt`
- [x] 3.3 Update both call sites (`NearbyTimelineScreen`, `GlobalTimelineScreen`) to pass the two new callbacks (wiring lands fully in §5)
- [x] 3.4 Extend `PostCardTest`: exactly-3-clickable-nodes semantics; like tap → `onToggleLike` only; reply tap → `onReplyShortcut` only; avatar-region tap → `onOpen`; no send node (negative guard); contentDescriptions present; like affordance announces toggled state (distinguishable accessible state liked vs not); reply affordance shows `replyCount` text + no numeric like count anywhere; liked-treatment switch still covered

## 4. DailyCapUpsellDialog component + countdown

- [x] 4.1 Add strings: `cap_dialog_title` ("Batas harian tercapai"), `cta_activate_premium` ("Aktifkan Premium"), `cap_countdown_hours_minutes` ("%1$d j %2$d mnt"), `cap_countdown_minutes` ("%1$d mnt") — body reuses existing `post_detail_likes_cap_upsell`, dismiss reuses existing `cta_close`
- [x] 4.2 Create the pure commonMain countdown formatter (round-up minutes; ≥60 min → hours+minutes split; ≤0 floored to 1 minute) + commonTest (51540 → "14 j 19 mnt" frame-18 fixture; 1140 → "19 mnt"; 59 → "1 mnt"; 3601 → "1 j 1 mnt"; 0 → "1 mnt" floor)
- [x] 4.3 Create `ui/components/DailyCapUpsellDialog.kt`: M3 `AlertDialog` per frame 18 (title, caller-supplied formatted body, filled "Aktifkan Premium" confirm → hoisted `onActivatePremium`, "Tutup" text dismiss → hoisted `onDismiss`, `onDismissRequest` = dismiss); minute tick via monotonic delay; auto-dismiss at zero; theme tokens + `stringResource` only
- [x] 4.4 Robolectric `DailyCapUpsellDialogTest` (v2 ComposeUiTest API): verbatim like-body render + both CTAs; scrim/back = dismiss; CTA callback routing; minute tick-down; zero auto-dismiss; `retryAfterSeconds = 0` floored (shows "1 mnt", no flash-dismiss on entry); light + dark scheme render; no-hardcoded-strings/token-only source guard — add to the Release-variant test-exclude list and verify `:mobile:app:testDevReleaseUnitTest`

## 5. Feed wiring (Nearby + Global)

- [x] 5.1 `NearbyTimelineViewModel`: delegate like toggles to the shared controller (LikeFlow via Koin); apply the optimistic flip to the tapped post within the retained `Loaded` outcome (single shared mechanism per design D2 — overlay vs in-place is the controller's internal detail) + expose the nullable cap state + `onLikeCapDialogDismissed()` following the existing multi-StateFlow shape; `PostGone` → existing `reload()`
- [x] 5.2 `GlobalTimelineViewModel`: same delegation to the SAME controller class (no per-feed duplicate)
- [x] 5.3 `NearbyTimelineScreen` + `GlobalTimelineScreen`: wire card `onToggleLike`/`onReplyShortcut`; render `DailyCapUpsellDialog` while the cap state is non-null with the like body (`post_detail_likes_cap_upsell` + live countdown); dismiss + `onActivatePremium` both clear the state (v1 placeholder wiring)
- [x] 5.4 ViewModel commonTests (both feeds): optimistic flip visible in list state; revert paths; cap state set/clear; reload-on-PostGone; in-flight guard via the controller
- [x] 5.5 Screen tests (both feeds): liked treatment flips on tap; 429 fake → dialog visible with verbatim copy; Tutup clears; "Aktifkan Premium" v1 wiring dismisses with NO back-stack append (host-level negative guard); reply-affordance pass-through (`onOpenPostReply` fires, `onOpenPost` does not); NetworkError → reverted with no error node (declared v1); list stays mounted throughout
- [x] 5.6 Source-guard test (the `ShellAndTimelineSourceGuardTest` precedent): both timeline ViewModels delegate to the shared inline-like controller class and no second like ApiClient/repository registration exists (pins the one-shared-implementation inspection scenarios)

## 6. Reply shortcut + autofocus (post-detail, tab host, routing)

- [x] 6.1 Add `focusReplyComposer: Boolean = false` to `PostDetailRoute` in `NavKeys.kt` (no SerializersModule change needed — defaulted field on a registered key)
- [x] 6.2 commonTest decode-compat: payload lacking `focusReplyComposer` decodes to `false` (mirror the `authorUsername` precedent test) + round-trip with `true`
- [x] 6.3 `PostDetailScreen`: consume-once composer autofocus (focus + IME request on first composition when the flag is true; no re-trigger on recomposition/focus-clear)
- [x] 6.4 `HomeScreen` + `AppShellScreen` + `AppEntryProvider`: hoist + forward `onOpenPostReply`; call-site appends `PostDetailRoute(..., focusReplyComposer = true)` (whole-card open stays `false`); Following tab wires nothing
- [x] 6.5 Tests: PostDetailScreen autofocus scenarios (true → composer focused; false → not; consumed-once; consumed marker survives saved-state restoration — state-restoration harness); tab-host wiring scenario (reply shortcut → route with `focusReplyComposer = true` on the root stack, both feeds)

## 7. Gates, manual verification, PR hygiene

- [x] 7.1 Full local gate: `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test :mobile:app:testDevDebugUnitTest :mobile:app:testDevReleaseUnitTest`
- [x] 7.2 Manual verify-loop bring-up (docs/11 § 5 DoD #3): Android emulator + iOS simulator — inline like flip/revert AND unlike of a liked post, 429 dialog (verbatim copy + countdown + Tutup + Aktifkan-Premium dismiss), reply shortcut autofocus + IME, whole-card open unchanged; screenshot evidence into the PR body
- [x] 7.3 Update PR #234 title (`feat(mobile): inline post actions — like, cap dialog, reply shortcut`) + body (summary, capability list, manual-verification evidence, "Closes #201") at the phase boundary
- [x] 7.4 At archive time: refresh the stale Purpose prose ("read-only post cards" / "single tap target" wording) in `mobile-post-card`, `mobile-nearby-timeline`, `mobile-global-timeline` alongside the spec sync (the PR #171 purpose-drift precedent) — and verify no "TBD - created by archiving" remains for `mobile-cap-upsell-dialog`
