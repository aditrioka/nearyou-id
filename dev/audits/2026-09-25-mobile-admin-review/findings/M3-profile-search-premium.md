# M3 — mobile profile, follow lists, search, premium, referral

### A. Completion matrix
| Capability | Reqs | Completion | Biggest gap |
|---|---|---|---|
| mobile-profile | 15 | ~93% | block/report network calls inside `MutableStateFlow.update` → CAS retry can re-send POST (C3); self profile never refreshes after first load (C4); `koinViewModel()` vs `viewModel{}` = baseline 05-#7 |
| mobile-follow-lists | 14 | ~97% | pager-swipe scenario untested (tab-tap only, `FollowListScreenTest.kt:196`); inline follow deferred (#307) |
| mobile-search | 15 | ~95% | result tap pushes default fields though by-id read exists (#255); back-affordance scenario untested |
| mobile-premium-username | 15 | ~93% | no post-purchase Premium re-check (C6); #333/#335 deferred |
| mobile-paywall | 11 | ~80% (end-to-end revenue loop 0%) | RevenueCat anonymous app-user id → webhook can't attribute purchases (C1); entitlement not confirmed after purchase (C2) |
| mobile-referral | 8 | ~95% | share-sheet deferred (#434); `ReferralApiClientTest` lacks transport-failure/cancel cases |
| client reqs in private-profile / premium-search / premium-username-customization / referral-read | – | implemented | Settings "Profil privat" toggle wired; search 403→upsell consumed; username PATCH+probe consumed; referral-read consumed |

### B. Follow-up validation
| # | Classification | Evidence | Scope |
|---|---|---|---|
| 252 | still-valid-openspec | no backend autocomplete route; `mobile-search` deferral (a) | Premium-gated, rate-limited `GET /api/v1/search/usernames?q=` → top-5 from `visible_users` via existing `gin_trgm_ops` index + block exclusion; ADD premium-search requirement; mobile dropdown; MODIFY mobile-search deferral (a) |
| 253 | still-valid-openspec (trigger fired) | seam exists: `UsernameCustomizationViewModel.kt:83-96` on-entry `isPremium` read; `SearchViewModel` has none | copy on-entry read into `SearchViewModel` → PremiumGate initial state when Free; keep 403 backstop; read failure → Idle. MODIFY mobile-search deferral (b) + "Search opens to Idle for all viewers" |
| 255 | still-valid-openspec (trigger fired) | `SinglePostApiClient.kt:44-67` `fetchFullPost` now returns city_name/liked_by_viewer/reply_count, used by notifications (`NotificationsRepository.kt:116`); search still pushes defaults (`AppEntryProvider.kt:351-360`) | hydrate via `fetchFullPost(postId)` on result tap, fallback to defaults on Unavailable, include imageUrl. MODIFY mobile-search § "A result tap opens PostDetailRoute with documented default fields" |
| 259 | still-valid-openspec | no bio/display-name writer (only private-profile/hide-distance/username/consent PATCH routes). Issue's "username is DESIGN" wording stale — username shipped in Settings | full vertical slice: `PATCH /api/v1/user/profile {displayName ≤50, bio ≤160}` + moderation; frame-8 edit dialog. MODIFY mobile-profile § "Edit-profile and suspension countdown are deferred" |
| 307 | still-valid-openspec (issue's "no backend change" is wrong) | `FollowListItem` (`FollowRoutes.kt:115-121`) has no viewer follow state | backend adds `followedByViewer` + `isSelf` (LEFT JOIN viewer follows); mobile row toggle reusing `ProfileViewModel` optimistic flip/revert/429/TargetGone. MODIFY follow-system list reqs + mobile-follow-lists § "Inline follow/unfollow on rows is deferred" |
| 333 | still-valid-openspec | `UserProfileResponse` (`UserProfileRoutes.kt:77-88`) has no `usernameLastChangedAt` | self-only `usernameLastChangedAt` on profile read (null for others); proactive cooldown state. MODIFY user-profile-read + mobile-premium-username deferral (a). Ship with #335 |
| 334 | still-valid-openspec | 409 body constant `{"error":"username_unavailable"}` (`UserUsernameRoutes.kt:143`) | `reason` (reserved/taken/release_hold) on 409 + probe → three messages (docs/03 §121-123 pre-9fbd70d4). Weigh anti-probing leak (premium-username-customization § Anti-probing) — operator may accept generic message as final |
| 335 | still-valid-openspec | copy at docs/03:102-103; no "customized" signal; signup never sets `username_last_changed_at` (nullable, `V2__auth_foundation.sql:20`) → non-null = customized | reuse #333 field; banner when `!isPremium && usernameLastChangedAt != null`. MODIFY deferral (c) |
| 336 | still-valid-openspec (spec cleanup only) | docs/03 username UX never specified autocomplete; only Search autocomplete (docs/03:244) = #252 | MODIFY mobile-premium-username to drop deferral (d), remove #336 link at docs/03:119, close as dup of #252. Fold into #333/#335 change |
| 434 | still-valid-openspec | no native share seam (no ACTION_SEND / UIActivityViewController) | platform `TextSharer` via Koin (docs/11 §2.5): Android ACTION_SEND, iOS UIActivityViewController; "Bagikan" next to "Salin kode"; fake-sharer test. MODIFY mobile-referral § "Native share-sheet is deferred" |
| 435 | still-valid-regular-pr | mockup board has 19 frames, none referral | docs-only: add frame 20 "Undang teman" + README index; then reconcile `ReferralScreen` |

### C. New gaps
1. **[high] Real purchases never unlock Premium server-side** — mobile-paywall § "The subscribe action drives the purchase…" + subscription-billing-webhook § "Purchase and renewal events activate Premium" — `KoinInit.kt:55-62` `configureRevenueCat(apiKey, appUserId = null)`, `Purchases.logIn` never called; contradicts archived design (`openspec/changes/archive/2026-06-17-mobile-paywall-screen/design.md:45` "appUserID SHALL be configured to the authenticated users.id"); webhook requires UUID `app_user_id`, 400 otherwise (`RevenueCatWebhookRoutes.kt:121-129`) → `subscription_status` never flips. Knock-on: search/username/radius/ads gates stay locked after paying; referral promo grants (target `users.id`, `ReferralActivityCheckWorker.kt:195`) invisible to client; no `logOut` on sign-out → next account inherits entitlement. KDoc says "tracked follow-on" but no issue exists — openspec (logIn on sign-in/session restore, logOut on sign-out)
2. **[medium] Paywall reports success when entitlement inactive** — mobile-paywall § "…confirm the entitlement is active" — `PaywallViewModel.kt:87` treats any `PurchaseResult.Success` as success, ignores `entitlementActive`; untested (`FakePurchaseController.kt:16` defaults true) — regular-pr
3. **[medium] Profile block/report can re-send POST** — mobile-profile § Block / § Report — `flow.block()`/`flow.report()` inside `state.update {}` (`ProfileViewModel.kt:120-125,137-143`); concurrent `onMessageShown()` (`:149`) triggers CAS retry → re-issued POST; report retry gets 409 "sudah dilaporkan", double rate-limit spend — regular-pr (call first, then update)
4. **[medium] Self profile stale after username change** — mobile-premium-username § "A successful change shows the success toast and returns to Settings" (profile reloads "on next composition/resume") — `ProfileViewModel` loads only in `init` (`:54-56`), HomeRoute-scoped (`ProfileScreen.kt:129`), no resume/pull refresh — regular-pr
5. **[medium] Paywall advertises unshipped "tenure Premium" badge** — mobile-paywall § "The paywall benefit set…" vs docs/03:95 "features available NOW" — `strings.xml:498` `paywall_benefit_no_ads` includes badge; tenure unimplemented (`SettingsScreen.kt:320` "deferred") — openspec
6. **[low] Gated surfaces don't re-check Premium after purchase** — mobile-paywall Purpose ("return-and-re-evaluate") — username gate fixed at on-entry read (`UsernameCustomizationViewModel.kt:83-96`); `PurchaseController.isPremiumEntitlementActive()` (`PurchaseController.kt:80`) never called — openspec
7. **[low] Paywall spec drift: IMAGE_ATTACH entry** — `PaywallEntry.IMAGE_ATTACH` wired (`AppEntryProvider.kt:187`, `PaywallScreen.kt:229`) but undeclared; `NavKeySerializationTest.kt:61-62` round-trips only LIKE_CAP + SEARCH_GATE — openspec (spec sync)
8. **[low] Scenario test gaps** — no back-affordance test in `SearchScreenTest`; no pager-swipe test (`FollowListScreenTest.kt:196`); no transport/cancel test in `ReferralApiClientTest.kt` — regular-pr

### D. Unspecced product surface
1. Profile "Postingan / Disukai" tabs + post count (frame 3, `nearyou-screens-mockup.html:779-790`) — no user-posts/likes endpoint, no spec
2. Profile location + "Gabung <bulan>" join-date line (frame 3, `:771-773`)
3. Premium Tenure Counter badge/chip with tier colours (`docs/01-Business.md:26`, Month-1 at `:88`; mockup `:768`) — no spec, no impl
4. "Perjalanan Premium" bottom sheet (frame 9, `:1322`) — Settings row → `onComingSoon` (`SettingsScreen.kt:322-327`)
5. "Kelola langganan" manage subscription (frame 16, `:1693`) → `onComingSoon` (`SettingsScreen.kt:328-333`); no issue; no restore-purchases action (no doc anchor found)
6. Premium tier-up notification "Badge Premium kamu naik ke Emas" (frame 4, `:872`) — depends on 3
7. 30-day release-hold FAQ entry (docs/03 § "30-Day Release Hold Explanation") — no FAQ surface
