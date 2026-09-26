# Mobile + admin spec-completion review — 2026-09-25

**Mandate (operator):** find out how complete the mobile app and the admin panel are against their OpenSpec specs, and which open `follow-up` issues in those areas are still real.

Why this lens, and not a quality/perf audit or an over-engineering audit:
- The backend was already reviewed twice (`2026-06-10-holistic-audit`, `2026-06-11-backend-review`).
- 3 of the 4 sessions active at review time were backend work.
- About 46 of the 69 open issues were mobile or admin.

**Method:** mirrors the 2026-06-11 backend review, run as six parallel read-only review agents, one per area:

| Area | Covers | Full report |
|---|---|---|
| M1 | shell, auth, settings, consent/analytics, test infra | [`findings/M1`](findings/M1-shell-auth-settings.md) |
| M2 | timelines, posts, ads | [`findings/M2`](findings/M2-timelines-posts-ads.md) |
| M3 | profile, search, premium, referral | [`findings/M3`](findings/M3-profile-search-premium.md) |
| M4 | chat, notifications, push | [`findings/M4`](findings/M4-chat-notifications-push.md) |
| A1 | admin moderation | [`findings/A1`](findings/A1-admin-moderation.md) |
| A2 | admin platform/ops + admin mockup frame map | [`findings/A2`](findings/A2-admin-platform-frames.md) |

- **Scoring:** each agent scored every requirement for implementation and for scenario-test coverage, citing `path:line`. The baseline for "already known" was the two previous audits plus every open issue.
- **Static only:** reading at `origin/main` 924685ff / 5a036285, with no gradle or test runs, because sibling sessions were building.
- **Verification:**
  - The coordinating session re-verified every **high** finding in source.
  - PRs #482 and #484 merged mid-review. The one finding they touched (#498) was re-verified against the new `main`.

**Out of scope:**
- Items in flight at review time: #271, #316, #347, #425.
- Backend-only and observability issues (#175–#180, #182, #212, #332, #381, #382).

## Headline

**Spec completion is high; the gaps are at the seams.**
- Most capabilities score 90–100% implemented+tested.
- The weak spots: `admin-appeal-review` ~65%, `mobile-crash-reporting` ~70%, `mobile-chat` ~80%, `mobile-push-message-handling` ~80%, `mobile-paywall` ~80%. Their gaps are in cross-surface state (entitlement identity, Premium re-evaluation, consent propagation) and in gates (roles, caps).

Six high-severity findings, all verified in source:

| # | Finding | Issue |
|---|---|---|
| 1 | **The revenue loop doesn't close.** RevenueCat is configured with `appUserId = null` (`KoinInit.kt:62`) and `Purchases.logIn` is never called. The webhook requires a UUID `app_user_id`, so real purchases never flip `subscription_status`. On the client, Premium is resolved once per screen, so even a correct flip wouldn't unlock the Nearby radius, ads, or username until a cold start. | #490 |
| 2 | **Moderators can approve appeals, and approval lifts permanent bans** (including CSAM bans). The appeal routes use `requireWriteRole` (owner/admin/moderator), while the spec says owner/admin. `GET /admin/appeals` has no role gate at all. | #491 |
| 3 | **Report-less `moderation_queue` rows are invisible to admins.** Nobody can see or clear keyword matches, Perspective auto-hides, area-spam or login anomalies unless a user also filed a report. The admin queue view starts `FROM reports`, and only `username_flagged` rows have their own surface (username oversight). | #438 (scope expanded) |
| 4 | **Declining crash reporting at onboarding doesn't stop Sentry for the session.** Crash reporting defaults to ON, and only the Settings path calls `applyConsent`. This is a UU PDP consent gap. | #492 |
| 5 | **The Free chat cap (429, 50/day) silently drops the message.** There's no upsell, and cap hits on post-detail, the composer and post-edit have no paywall path either. | #493 |
| 6 | The Nearby radius Premium gate never re-resolves after a purchase. | folded into #490 |

**Launch-relevant, but no spec yet:**
- **Sign in with Apple:** App Store Review 4.8 gate; on the roadmap at `docs/08:170`.
- **Restore Purchases:** `docs/08:388`; now tracked in #502.
- **Admin WebAuthn and a separate admin service behind IAP:** `docs/08:199-201`, "mandatory before 2nd admin hire".

## Completion matrix

Per-requirement evidence is in each findings file.

**Mobile**

| Area | Capability | Completion | Biggest gap |
|---|---|---|---|
| M1 | mobile-app-scaffold | ~95% | iOS tests not CI-gated (#348) |
| M1 | mobile-auth-signin | ~95% | sign-in diagnostics unwired (#492) |
| M1 | mobile-age-gate | ~98% | — |
| M1 | mobile-analytics-consent | ~92% | iOS durable-store test (#400) |
| M1 | mobile-amplitude-analytics | ~100% of spec | taxonomy / identify / app_opened are spec'd deferrals (#395–#397) |
| M1 | mobile-settings | ~90% | deferred rows lost their tracker (#502); legal-row test (#500) |
| M1 | mobile-location | ~95% | — |
| M1 | mobile-home-tab-host | ~92% | iOS flow tests red (#348) |
| M1 | mobile-design-system | ~92% | language switch deferred (#203) |
| M1 | mobile-crash-reporting | ~70% | consent decline, logout clear-user, sign-in diagnostics (#492); symbol upload (#504) |
| M1 | shared-resources | ~90% | catalog guard stopped guarding (#479) |
| M1 | mobile-appeal | ~95% | permanent-ban form untracked (#503) |
| M2 | mobile-nearby-timeline | ~98% | no reload after composer (#173) |
| M2 | mobile-following-timeline | ~97% | kebab/block paths tested on Global only (#500) |
| M2 | mobile-global-timeline | ~100% | — |
| M2 | mobile-nearby-radius-slider | ~90% | no post-purchase re-resolve (#490) |
| M2 | mobile-post-card | 100% | send action deferred (#238) |
| M2 | mobile-post-creation | ~95% | iOS flow test (#174); cap banner (#493) |
| M2 | mobile-post-detail | ~95% | frame-7 restyle (#242); cap banner (#493) |
| M2 | mobile-post-editing | ~85% | dead "Aktifkan Premium" CTA (#493) |
| M2 | mobile-image-attachment | ~95% | `IosImagePicker` untested |
| M2 | mobile-block-from-content / mobile-content-report | ~95% | feed paths tested on Global only (#500) |
| M2 | mobile-cap-upsell-dialog | 100% | used only by the 3 feeds (#493) |
| M2 | mobile-ads | ~85% | no re-gate on upgrade or account switch (#490) |
| M3 | mobile-profile | ~93% | POST re-send + stale self profile (#498) |
| M3 | mobile-follow-lists | ~97% | inline follow (#307) |
| M3 | mobile-search | ~95% | default fields on tap (#255) |
| M3 | mobile-premium-username | ~93% | no post-purchase re-check (#490) |
| M3 | mobile-paywall | ~80% (0% end-to-end) | anonymous RevenueCat id; entitlement not confirmed (#490) |
| M3 | mobile-referral | ~95% | share-sheet (#434) |
| M4 | mobile-chat | ~80% | send-failure states + pull-to-refresh (#494); cap (#493) |
| M4 | mobile-chat-embedded-posts | ~95% | edited-since-shared banner (#440) |
| M4 | mobile-chat-message-report | ~95% | iOS-sim run owed (#383) |
| M4 | mobile-notifications-list | ~95% | actor username (#194); reply / actor-less deep links (#379) |
| M4 | mobile-push-message-handling | ~80% | iOS inert (#430, #495) |
| M4 | mobile-fcm-token-registration | ~85% | FirebaseApp never configured on iOS (#495) |

**Admin**

| Area | Capability | Completion | Biggest gap |
|---|---|---|---|
| A1 | admin-report-queue | ~97% | report-less queue rows invisible (#438); edit-history filter (#191) |
| A1 | admin-appeal-review | ~65% | role gate (#491) |
| A1 | admin-user-moderation | ~99% | — |
| A1 | admin-user-management | ~95% | history misses queue/appeal enforcement (#496) |
| A1 | admin-block-registry | 100% | index (#279, deferred) |
| A1 | admin-chat-message-redaction / csam-detection-log / wordlist-editor / post-edit-history / rejected-identifiers / actions-log | 100% | — |
| A2 | admin-login / schema / referral-manual-grant / reserved-usernames / destructive-action-rate-limit | 100% | — |
| A2 | admin-panel-scaffold | 95% | sidebar requirement stale (#499) |
| A2 | admin-operational-dashboard | 100% of spec | two deferred widgets now have sources (#303) |
| A2 | admin-feature-flags | 95% | spec omits a shipped flag (#499) |
| A2 | admin-data-export-queue / hard-delete-queue | 100% | icon-name text leak (#501) |
| A2 | admin-privacy-flip-monitor | 100% | write action (#277) |
| A2 | admin-subscription-grace-monitor / premium-username-oversight | 98% | test gaps (#500) |

Admin invariants were checked across every mutating route and all hold:
- CSRF runs first, then the role check.
- Every write logs to the audit log in the same transaction.
- The destructive cap is applied in the same transaction.
- Admin-user FKs are `ON DELETE SET NULL` (`admin_actions_log.admin_id` is NO ACTION by documented design).
- Raw-table reads stay inside `id.nearyou.app.admin.*`.

## Existing follow-up issues: validation

**Labeled `ready-to-burndown` (39).** Each got a comment with its vetted scope and evidence:

| Area | Issues |
|---|---|
| M1 | #204, #266 (part 2 only), #275, #348, #395, #396, #397, #400, #479 |
| M2 | #173, #174, #238 (its dependency #482 merged), #242, #338, #442, #443 |
| M3 | #252, #253, #255, #259, #307, #333, #335, #434, #435 |
| M4 | #194, #197, #272, #286, #379, #383, #390, #440 |
| A1 | #191, #392, #438 |
| A2 | #277, #282, #303 |

The review corrected several issue bodies (details in the comments):
- **#307:** does need a backend change. The follower-list rows carry no viewer follow state.
- **#253, #255:** their trigger has fired.
- **#259:** its "username is DESIGN" wording is stale.
- **#266:** part 1 already shipped; part 2 now matters on reinstall.
- **#348:** the DI drift has grown, including a new `DataExportFlowIosTest` gap.
- **#277:** must re-check `subscription_status` before applying a flip.

**Needs an operator decision (not labeled):**
- **#334:** weigh the anti-probing leak against three distinct unavailable-username messages.
- **#336:** spec cleanup only, to fold into the #333/#335 change; then close as a duplicate of #252.

**Still valid, deferred (trigger not fired):**

| Issue | Trigger |
|---|---|
| #186 | Can't fire until #492 wires sign-in diagnostics |
| #203 | Decision to ship a non-ID locale |
| #258 | Operator Firebase/APNs setup; the code side is #495 |
| #278, #279 | A scale signal |
| #280 | iOS Supabase config must land first |
| #315 | Product decision |
| #393 | First observed PII in `appeal_text`, or the pre-launch UU PDP review |
| #430 | Operator Xcode setup; pairs with #495 |
| #444 | Phase 2+ and live AdMob fill |

**Close candidates (awaiting operator confirmation):**
- **#189** (per-tab back stacks): superseded. Every intra-tab destination shipped as a root-stack push, and `mobile-home-tab-host` re-asserts "no per-tab NavDisplay".
- **#398** (admin Amplitude embed): a duplicate of #303's "Amplitude funnel embed" bullet.

## New findings: issues filed

| Issue | Severity | Shape | Findings folded in |
|---|---|---|---|
| #490 | high | OpenSpec | RevenueCat identity, entitlement confirmation, post-purchase re-evaluation (radius, ads, username, search) — M3-C1/C2/C6, M2-C1/C2 |
| #491 | high | regular PR | appeal role gate + OFFSET pagination + route tests — A1-C1/C4/C5 |
| #438 (comment) | high | OpenSpec | generic report-less `moderation_queue` viewer — A1-C2 |
| #492 | high | regular PR | onboarding crash-consent decline, logout clear-user, sign-in diagnostics — M1-C1/C2/C3 |
| #493 | high | OpenSpec | cap/gate upsell parity (chat 429, post-detail, composer, edit CTA, paywall entries, tenure-badge copy) — M4-C1, M2-C3/C4/C8, M3-C5/C7 |
| #494 | medium | regular PR | chat send-failure states, thread pull-to-refresh, first-send prompt — M4-C2/C5/C8 |
| #495 | medium | OpenSpec (deferred) | iOS push code gaps — M4-C3/C4/C7 |
| #496 | medium | OpenSpec | admin user history misses queue/appeal enforcement — A1-C3 |
| #497 | medium | OpenSpec | own-reply delete client — M2-C5 |
| #498 | medium | regular PR | ProfileViewModel POST re-send + stale self profile — M3-C3/C4 |
| #499 | low | OpenSpec | spec hygiene: six stale canonical specs — M1-C6, M4-C10, A1-C6, A2-C2/C3 |
| #500 | low | regular PR | spec'd test-scenario gaps + null-preview fallback — M2-C6, M3-C8, M4-C9/C11, M1-C7, A2-C4 |
| #501 | medium | regular PR | admin icon names render as literal text — A2-C1 |
| #502 | medium | OpenSpec | Kelola langganan + Restore Purchases + Perjalanan Premium — M1-C5, M3-D5 |
| #503 | low | OpenSpec (deferred) | permanent-ban appeal form — M1-C5 |
| #504 | low | operator (deferred) | Sentry symbol upload — M1-C4 |

Two low findings were folded instead of filed:
- The absolute "Diedit" date (M2-C7) goes into the existing relative-timestamp roadmap item (`docs/08:192`).
- The missing @username and badge on conversation rows (M4-C6) goes into #286.

## Unspecced product surface (input for `/next-change`)

Features the product docs or mockups describe that have **no spec and no implementation**. These are net-new capability, not debt, so no issues were filed.

| Area | Features |
|---|---|
| Launch-relevant | Sign in with Apple (`docs/08:170`). Admin account security, frame 14: WebAuthn enrollment plus active sessions with revoke (`docs/08:201`). Admin-user administration: create/deactivate/role change (`docs/07` § Security Layer 2). Admin IP allowlist. Separate `admin.nearyou.id` service behind IAP (`docs/08:199`). |
| Onboarding | Guest Global browse + login wall (`docs/03` § First App Open). Onboarding carousel (mobile frames 10–12). Post-signup username reveal. Onboarding FAQ / account-loss disclosure (`docs/02:37`). |
| Profile & Premium | Profile "Postingan / Disukai" tabs, post count, location, join date (frame 3). Premium tenure badge (`docs/01:26`), which unlocks "Perjalanan Premium" (frame 9) and the tier-up notification (frame 4). Premium badge on post cards and reply rows. |
| Posts | Author self-delete of posts (`docs/02:114`; no user `DELETE /api/v1/posts/{id}`). "Ikuti" in the post-detail header, and reply likes (frame 7). |
| Chat | List search, last-message preview, presence dot, new-message FAB (frame 2). Read receipts, per-message timestamps, partner header (frame 5). Pending-embed preview. Soft-deleted-embed banner and "Riwayat edit" modal (`docs/03` § Chat Context Card UX). Permission prompt on first message received (`docs/03:74`). |
| Notifications | Per-type icon containers; day grouping (frame 4). |
| Admin | Attestation Fallback Review (admin frame 11). `admin_flag` action. Date-range and by-user filters on username history (`docs/07`). |

## Admin mockup frame map

The board has 23 frames: **19 shipped, 2 partial, 2 not started**. Full table: [`findings/A2`](findings/A2-admin-platform-frames.md).
- **Partial:** frame 3 (dashboard; deferred widgets, #303) and frame 6 (user profile; not redlined, stat strip missing, #282).
- **Not started:** frame 11 (Attestation Fallback Review) and frame 14 (Account Security).
- `/admin/appeals` ships but has no frame (#392).

## Doc drift noticed (not fixed here)

- **CLAUDE.md** says the admin UI is "intentionally unstyled so far". That's stale:
  - The board's design language landed in #226/#241 (`admin.css`, 760 lines).
  - But `admin.css` hasn't changed since #290 (2026-06-14), so surfaces built later have unstyled frame-specific classes (#501, #282).
- **Admin board status tags:** frames 16 and 19–22 are still tagged "Usulan" (proposed) although they shipped.
- **Mobile board:** has no referral frame (#435).

## Recommended burn order

1. **#490:** the revenue loop. It's a pre-launch blocker, and it unblocks #502 and every post-purchase gate.
2. **#491:** appeal role gate. A small regular PR; authorization.
3. **#492:** crash-consent decline. A small regular PR; UU PDP.
4. **#438** (expanded): report-less moderation-queue viewer. Auto-hidden false positives currently have no admin `keep` path.
5. **#493:** cap/gate upsell parity. Revenue plus a silent message-loss UX bug.
6. **#501:** admin icon text leak. Tiny.

After these, the remaining `ready-to-burndown` queue, in any order. Sign in with Apple should go through `/next-change` before any store submission.
