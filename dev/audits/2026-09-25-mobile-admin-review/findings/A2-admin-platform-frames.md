# A2 — admin platform + ops, admin mockup frame map

### A. Completion matrix
| Capability | Reqs | Completion | Biggest gap |
|---|---|---|---|
| admin-login | 15 | 100% | — (AdminLoginRouteTest, AdminSessionMiddlewareTest, AdminCsrfMiddlewareTest, AesGcmCipherTest, TotpVerifierTest, AdminAuthConstantTimeScanTest; keys via `secretKey(env,…)` Application.kt:1401,1413) |
| admin-panel-scaffold | 4 | 95% | layout requirement stale: spec.md:66 "exactly the five shipped nav items" vs 16 shipped (layout.peb:54-75; test asserts 16, AdminPanelScaffoldAuthTest.kt:85) |
| admin-schema | 8 | 100% | — (MigrationV16SmokeTest); WebAuthn tables unused by design |
| admin-operational-dashboard | 7 | 100% of spec | frame 3 partial; deferred cluster #303 — two sources landed since |
| admin-feature-flags | 11 | 95% | wordlist edit-affordance link (feature-flags-content.peb:92) untested; catalog has editable `premium_image_upload_cap_override` (FeatureFlagCatalog.kt:53) the spec omits |
| admin-data-export-queue | 5 | 100% | `.ms` icon-text leak (C1) |
| admin-hard-delete-queue | 9 | 100% | `.ms` icon-text leak (C1) |
| admin-privacy-flip-monitor | 12 | 100% | read-only by spec; in-panel fix = #277 |
| admin-subscription-grace-monitor | 8 | 98% | soft-deleted branch of "expedite rejected outside window" (SubscriptionGraceRepository.kt:322) untested |
| admin-premium-username-oversight | 15 | 98% | flag-queue keyset paging (UsernameOversightRepository.kt:55-94) tested only with malformed cursor |
| admin-referral-manual-grant | 9 | 100% | — |
| admin-reserved-usernames-editor | 8 | 100% | — |
| admin-destructive-action-rate-limit | 4 | 100% | — (CSAM takedown checks cap but doesn't count, deliberate — csam design D5) |

Invariants hold: every POST calls `AdminCsrfGate.validateCsrf` (CSRF-first then role); every spec'd write audits in-tx; destructive cap in-tx (UserModerationRepository:103, ReportResolutionRepository:164, ChatRedactionRepository:107) + distinct counters for export/expedite/grace/referral/reserved/oversight.

### B. Follow-up validation
| # | Classification | Evidence | Scope |
|---|---|---|---|
| 303 | still-valid-openspec | dashboard queries only posts/users/rejected_identifiers/reports/admin_actions_log/pg_database_size (AdminIndexStatsRepository.kt:93-268); sources now exist: `subscription_events` (V21:23), `granted_entitlements` (V32:28), `csam_detection_archive` (V31:26) | MODIFY admin-operational-dashboard § "Operational widgets whose data source does not yet exist are deferred"; ADD (a) Premium-active paid-vs-referral tile, (b) CSAM detection event count (24h/7d, identity-free); update absence list in AdminDashboardTemplateTest.kt:63-68. Rest stays deferred (attestation, token reuse, RC sig-fail, DAU/MAU, Sentry/Amplitude/Resend/Realtime, DB-size trend) — #303 stays open |
| 282 | still-valid-regular-pr | user-profile.peb unchanged since #373; frame-6 classes `profile-head`/`three-col`/`history-merged`/`statebox` (user-profile.peb:19,38,134,144) have no admin.css rules (last touched #290); history still inlined | `mockup-measure.sh … 6` (+7), add CSS rules, decide history-fragment extraction. Frame-6 "Joined/Posts/Followers" strip adds data → separate admin-user-management MODIFY, keep out of styling PR |
| 278 | still-valid-defer | only single-column `users_privacy_flip_idx` (V2:35); pre-launch | Trigger: EXPLAIN/latency on `/admin/privacy-flips` shows incremental-sort cost in a mass-scheduling event |
| 277 | still-valid-openspec | GET only (AdminPrivacyFlipsRoute.kt:56); negative guard "adds only read routes" (AdminPrivacyFlipsRouteTest.kt:309) | MODIFY + ADD `POST /admin/privacy-flips/{userId}/resolve`: CSRF→owner/admin, reason, OVERDUE only, one guarded tx + audit. **Re-check `subscription_status`**: still non-premium → apply worker UPDATE (PrivacyFlipWorker.kt:106); re-subscribed → clear schedule only (else a paying user's profile goes public). Distinct audit-ledger counter, not destructive budget |
| 398 | still-valid-defer → **duplicate of #303** | no Amplitude on admin (absence asserted AdminDashboardTemplateTest.kt:68); duplicates #303's "Amplitude funnel embed" bullet | Trigger: post-launch + Amplitude data + external-embed/CSP decision. Recommend close as dup of #303 |

### C. New gaps
1. **[medium] Icon names render as literal text** — templates use Material-Symbols `<span class="ms">name</span>` but admin.css has no `.ms` rule and admin/static vendors no icon font (only Plus Jakarta Sans) → operators see "boltExpedite now", "play_arrowTrigger job", "boltInvoke takedown handler", "info", "warning". Spec: admin-panel-scaffold § Shared base layout (vendored inline-SVG `icon()` with `data-icon`). Evidence (11 spans): deletion-requests.peb:47, deletion-requests-table.peb:65, data-exports.peb:46, data-exports-table.peb:66, csam-log.peb:14,24,41,83,90, csam-log-table.peb:67,86 — regular-pr (replace with `{{ icon(…) }}`, add bolt/play_arrow/warning/gavel/filter_alt to icons.peb)
2. **[low]** Scaffold spec stale on sidebar ("exactly five nav items, no Feature flags" never MODIFIED as 11 surfaces landed) — spec.md:66 vs layout.peb:54-75 — openspec (spec sync)
3. **[low]** Feature-flags spec "exactly the canonical flag catalog" omits shipped editable `premium_image_upload_cap_override` (spec.md:25 vs FeatureFlagCatalog.kt:53, FeatureFlagCatalogTest.kt:38; its own spec premium-image-upload/spec.md:63) — openspec
4. **[low]** Test gaps: wordlist edit-affordance link unasserted (AdminFeatureFlagsRouteTest.kt:426 predates delegation); flag-queue keyset paging; grace expedite on soft-deleted user — regular-pr

### D. Admin mockup frame map (23 frames: 19 shipped, 2 partial, 2 not started)
| Frame | Surface | Status | Evidence / missing |
|---|---|---|---|
| 1 | Login | shipped | AdminLoginRoutes.kt:60-61 |
| 2 | Shell | shipped | layout.peb:54-75 |
| 3 | Operational Dashboard | **partial** | volume/age-gate/cities/DB size shipped; DAU/MAU, Sentry, anomaly banner, CSAM, attestation, token-reuse, RC sig-fail, Resend, subscriptions & cost, health, embeds missing (#303/#398) |
| 4 | Report Queue + in-row resolution | shipped | edit-history filter missing (#191) |
| 4b | Responsive contract | shipped | admin.css:645-728 |
| 5 | User lookup | shipped | AdminUserModerationRoute.kt:64 |
| 6 | Profile + history | **partial** | not redlined; stat strip absent (#282) |
| 7 | Audit log | shipped | AdminActionsLogRoute.kt:41 |
| 8 | Post edit history | shipped | AdminPostEditsRoute.kt:52 |
| 9 | Chat redaction | shipped | AdminChatRedactionRoute.kt:56,71 |
| 10 | Rejected identifiers + clear | shipped | AdminRejectedIdentifiersRoute.kt:70,74 |
| 11 | Attestation Fallback Review | **not started** | (A1) |
| 12 | Block registry | shipped | AdminBlockRegistryRoute.kt:49 |
| 13 | CSAM | shipped | AdminCsamRoute.kt:75-160 |
| 14 | Account Security (WebAuthn + sessions) | **not started** | no `/admin/security`; V16 WebAuthn tables touched only by retention cleanup |
| 15 | Hard delete queue | shipped | AdminDeletionQueueRoute.kt:62,66 |
| 16 | Data export queue | shipped | AdminDataExportQueueRoute.kt:59,63 |
| 17 | Privacy flips | shipped (read-only) | write = #277 |
| 18 | Subscription grace | shipped | AdminSubscriptionGraceRoute.kt:57,61 |
| 19 | Referral grants | shipped | AdminReferralGrantsRoute.kt:60,64 |
| 20 | Feature flags + wordlist editor | shipped | AdminFeatureFlagsRoute.kt:46,50 |
| 21 | Reserved usernames | shipped | AdminReservedUsernamesRoute.kt:58-131 |
| 22 | Username oversight | shipped | AdminUsernameOversightRoute.kt:62-107 |

`/admin/appeals` ships but has no frame (#392). Board status tags stale: frames 16, 19–22 still tagged "Usulan" (nearyou-admin-mockup.html:1266,1396,1454,1507,1563).

**Styling:** CLAUDE.md's "UI intentionally unstyled" is no longer true — board design language applied in #226/#241 (admin.css 760 lines, tokens :26, 4b responsive :645-728). But admin.css untouched since #290 (2026-06-14): later surfaces reuse shared classes while frame-specific classes (`ff-*`, `wl-*`, `profile-head`, `three-col`) have no rules; none redlined against `mockup-measure.sh`; three carry the `.ms` leak (C1).

**Unspecced (no spec, no impl):**
1. Admin Account Security — WebAuthn enrollment/login + active admin sessions list/revoke (frame 14; docs/07 § Security Layer 2 "mandatory before 2nd admin hire"; docs/08:201)
2. Admin-user administration — create/deactivate admins, change roles, rotate session on role escalation (docs/07 § Security Layer 2); today only CLI `dev/AdminBootstrapMain.kt`
3. Admin IP allowlist (VPN static IP) (docs/07 § Security Layer 2)
4. Separate `admin.nearyou.id` Cloud Run service behind IAP / Cloud Armor (docs/07 § Security Layer 1; docs/08:199); today mounted in API service (Application.kt:1393)
5. Date-range + by-user filters on username-history viewer (docs/07 § Premium Username Change Oversight; spec has substring `q` only)
