# M2 — mobile timelines, posts, post card, ads

### A. Completion matrix
| Capability | Reqs | Completion | Biggest gap |
|---|---|---|---|
| mobile-nearby-timeline | 21 | ~98% | No feed reload after composer return (#173); spec's `ON_RESUME` claim is wrong — `ON_RESUME` only refreshes the location gate (`NearbyTimelineScreen.kt:147-149`) |
| mobile-following-timeline | 16 | ~97% | report/block own-post gating + block-removal untested here (Global only) |
| mobile-global-timeline | 15 | ~100% | — |
| mobile-nearby-radius-slider | 8 | ~90% | Premium tier resolved once per HomeRoute VM → post-purchase user stays 20 km-locked (C1) |
| mobile-post-card | 8 | 100% | send action spec-deferred (#238); relative time deferred (`docs/08:192`) |
| mobile-post-creation | 13 | ~95% | iOS flow test missing (#174); 429 banner has no Premium path (C4) |
| mobile-post-detail | 23 | ~95% | not styled to frame 7 (#242); cap hits = banner, no paywall path (C4) |
| mobile-post-editing | 9 | ~85% | "Aktifkan Premium" CTA is a dead control (C3); "Diedit" absolute date not relative (C7) |
| mobile-image-attachment | 5 | ~95% | `IosImagePicker` has no automated test |
| mobile-block-from-content | 8 | ~95% | feed-level block path tested only on Global |
| mobile-content-report | 9 | ~95% | feed-level report path tested only on Global |
| mobile-cap-upsell-dialog | 4 | 100% | used by 3 feeds only; post-detail keeps banner (declared divergence, C4) |
| mobile-ads | 11 | ~85% | no mid-session re-gate on upgrade/account switch (C2); iOS actual verified manually only |
| distance-rendering (client) | 5 | 100% | — |

### B. Follow-up validation
| # | Classification | Evidence | Scope |
|---|---|---|---|
| 173 | still-valid-openspec | `AppEntryProvider.kt:183` `onPostCreated` only pops back stack; HomeRoute-scoped feed VM survives; nothing reloads | Koin-single `PostCreatedSignal` (counter StateFlow) bumped by `CreatePostRepository` on Success; Nearby/Global VMs collect → `reload()`. MODIFY mobile-post-creation § "Successful post returns to Home; Nearby auto-refresh on return is deferred" + mobile-nearby-timeline § "…survives the composer round-trip" (wrong `ON_RESUME` claim). Same signal = #442 post-submit hook |
| 174 | still-valid-regular-pr | `iosTest/.../screens/post/` has only `PostDetailFlowIosTest.kt` | Add `PostCreationFlowIosTest.kt` mirroring `NearbyTimelineFlowIosTest` (FakeCreatePostFlow + FakeImagePicker, kotlin.test). Suite already red (#348) — verify new test green in isolation |
| 242 | still-valid-regular-pr | `PostDetailScreen.kt:595-640` BackBar = "Tutup" TextButton + kebab; frame 7 wants arrow_back + "Postingan" app bar, "N balasan" header, card-style action row, composer send icon. #234 merged → precondition met | Restyle BackBar→TopAppBar, like row, replies header, composer; docs/11 §2.8 annex. Exclude behavior changes ("Ikuti", reply likes, send) |
| 238 | still-valid-openspec | `PostCard.kt:133-134` absent by spec; only post→chat path is post-detail "Bagikan ke chat" → picker of existing conversations. PR #482 lists feed cards as Non-Goal → not in-progress | MODIFY mobile-post-card § "Send-message card action is deferred": hoisted `onSendMessage`, fail-closed self-author gate; feed VMs call `ChatFlow.createOrReturn(authorUserId)` reusing #482's plumbing; MODIFY mobile-chat for pending embed preview sent with `embedded_post_id`. Sequence after #482 |
| 338 | still-valid-openspec | timeline DTOs/`TimelineRoutes` no edited field; `posts` has no `edited_at` (only `post_edits(post_id, edited_at DESC)` index, `V22__post_edits.sql:29`) | per-row `editedAt` (correlated MAX over index, or denormalized `posts.edited_at` in edit tx) → 3 DTOs + `PostCardModel.editedAt` + badge. MODIFY mobile-post-editing § "does not add a timeline-card edited indicator", 3 mobile timeline specs, mobile-post-card, backend nearby/following/global-timeline. Shares "is edited" source with #440 |
| 442 | still-valid-openspec | mobile-ads § "ships only the timeline native placement" guard in force; `AdProvider` has no interstitial API | interstitial load/show on `AdProvider` (both actuals); persistent app-open counter (DataStore); post-submit 1-in-5 via #173 signal; gate on `AdFeedController`; never on chat thread. Buildable dark with `AdTestUnits.kt`; real fill blocked on AdMob approval |
| 443 | still-valid-openspec | same guard; `TimelineAdsScreenTest.kt:121` asserts ad seam used only by 3 timeline screens | banner format on `AdProvider`, other-user profile banner, conversation-list native slot; relax that test. MODIFY mobile-ads, mobile-profile, mobile-chat |
| 444 | still-valid-defer | mobile-ads § "does not add ad mediation" | Trigger: docs/01 Phase 2+ AND live AdMob fill (`ads_enabled` kill-switch default OFF) |

### C. New gaps
1. **[high]** Nearby radius Premium gate never re-resolves after purchase — spec: mobile-nearby-radius-slider § "Premium tier selects freely" / § "On-entry tier resolution and reactive 403 backstop" — evidence: `resolvePremiumOnEntry()` only from `init` (`NearbyTimelineViewModel.kt:199-201,339`); HomeRoute VM survives paywall (`AppEntryProvider.kt:160`); purchase success broadcasts nothing (`PaywallViewModel.kt:87`). Upsell → buy → return still snaps back until cold start — openspec
2. **[medium]** Ads not re-gated on mid-session upgrade or account switch — mobile-ads § "Premium viewers see zero ads" — process-lifetime `prepared` latch (`AdFeedController.kt:56-57`, `MobileModule.kt:414-421`), never reset on purchase/sign-out; latent while ads OFF — regular-pr + scenario
3. **[medium]** Post-edit "Aktifkan Premium" CTA is dead — mobile-post-editing § "Premium gating is reactive on the backend 403" — `EditPostScreen.kt:90,171` (dismiss-only, "v1 has no paywall"); paywall shipped since (#309), `AppEntryProvider.kt:205-213` wires none — openspec (new `PaywallEntry` value, `NavKeys.kt:274-279`)
4. **[medium]** Cap hits on post-detail (like/reply) + composer (10/day) have no Premium path — error-colored banner with coarse "N jam" vs `docs/03:184-189` modal + realtime countdown + "Aktifkan Premium" — mobile-post-detail § "Like toggle…cap upsell" / § "Reply composer…cap upsell" — `PostDetailScreen.kt:1111-1127`, `PostCreationScreen.kt:196`; divergence declared in mobile-cap-upsell-dialog but untracked; Free reply cap never reaches paywall — openspec
5. **[medium]** Own-reply delete has no client — post-replies § "DELETE replies — author-only soft-delete, idempotent 204" — `ReplyRoutes.kt:165`, no caller in mobile commonMain, no declared deferral (docs/12 cohesion) — openspec
6. **[low]** Own-post kebab gating + block-removal tested only on Global though each VM has its own `removeAuthorPosts` copy — `GlobalTimelineScreenTest.kt:447-540`, `GlobalTimelineViewModelTest.kt:325`; untested `NearbyTimelineViewModel.kt:177-186` — regular-pr
7. **[low]** "Diedit" renders ISO date, spec says relative — mobile-post-editing § "Edited posts display a 'Diedit' label" — `PostDetailScreen.kt:806` — regular-pr (fold into docs/08:192 relative-timestamp item)
8. **[low]** Radius upsell opens paywall as `LIKE_CAP` → like-cap headline — mobile-paywall § "PaywallScreen renders the frame-17 paywall surface" — `AppEntryProvider.kt:160` — regular-pr or bundle with C3

### D. Unspecced product surface
1. Author self-delete of own posts (`docs/02:114` "Soft delete only") — no user `DELETE /api/v1/posts/{id}`, spec, or mobile affordance
2. "Ikuti" follow button in post-detail header (frame 7, `nearyou-screens-mockup.html:1181`)
3. Reply likes + per-reply like counts (frame 7)
4. Premium badge next to author on post cards / reply rows (`docs/01:26`; frame 7) — only on profile/follow-list; timeline/reply wire carries no premium flag
