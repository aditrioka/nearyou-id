# NearYouID - Master Design Document

## Project Overview

Location-based social media MVP. Users create text posts with location, discover nearby posts, and subscribe to Premium for advanced features. Modular monolith in Kotlin Multiplatform.

## Guiding Principles

1. **Vibe coding first**: Development is primarily AI-assisted. Complexity during the build phase is cheap. Optimize for AI-friendly patterns (clean interfaces, isolated modules, standard frameworks).
2. **Minimize runtime cost**: Flat/predictable pricing preferred over pay-per-use. Free tier maximized for phases 0-1.
3. **Minimize maintenance overhead**: Oka will manage ops himself without AI assistance. Pick managed services for anything that causes headaches (DB ops, auth, backup).
4. **Portable by design**: Abstraction layer across all vendor integrations so future migrations are frictionless.

---

## Document Index

This master plan consists of the following domain-focused files:

| # | File | Scope |
|---|------|-------|
| 00 | **README.md** | Overview, guiding principles, document index (this file) |
| 01 | **01-Business.md** | Business model, freemium tiers, pricing, payment stack, ads, referrals, GTM strategy, financial forecast, success metrics |
| 02 | **02-Product.md** | Core feature specifications: user management, post system, timeline, social (follow/like/reply/report), direct messaging, premium media upload, block, search, notifications |
| 03 | **03-UX-Design.md** | UX copy strategy, user onboarding flows, empty states, permission flows, consent flows, notification content UX |
| 04 | **04-Architecture.md** | Tech stack, system architecture diagrams, dependency isolation, backend modules, deployment strategy, observability stack, backup strategy, push notification infrastructure, email infrastructure, health checks |
| 05 | **05-Implementation.md** | Database schemas (all tables), algorithms (jitter, rotation, race-safe patterns), auth/session implementation, rate limiting implementation, cache key formats, feature flags, key implementation notes |
| 06 | **06-Security-Privacy.md** | Device attestation, anti-spam, content moderation, CSAM handling, shadow ban, privacy compliance (UU PDP), age gate (18+ only), under-18 signup blocklist, internal endpoint security, analytics consent, account recovery/deletion |
| 07 | **07-Operations.md** | Admin panel stack, admin user schema, core features, security (IAP/Cloud Armor/WebAuthn), DB access pattern |
| 08 | **08-Roadmap-Risk.md** | Development phases (Pre-Phase 1 through Public Launch), development tools & CI lint rules, risk register, open decisions |

## Reading Order Suggestions

- **For business stakeholders**: 00 → 01 → 02 → 08 (risk register)
- **For product/design**: 00 → 02 → 03
- **For engineers**: 00 → 04 → 05 → 06 → 07 → 08 (phases)
- **For security/compliance**: 00 → 06 → 05 (schemas) → 07 (admin)
- **For investors/financial review**: 00 → 01 → 08

## Cross-File References

Many topics span multiple files. Key cross-references:

- **Authentication**: Business policy (01) → Feature spec (02) → UX flow (03) → Implementation (05) → Security detail (06)
- **Chat/Direct Messaging**: Feature (02) → Architecture/Realtime (04) → Schemas + Redis Streams (05) → Moderation hooks (06)
- **Premium Media**: Feature (02) → CSAM architecture (04) → Upload flow + schema (05) → CSAM + Kominfo (06)
- **Rate Limiting**: Product limits (02) → Cache keys (05) → Defense-in-depth layers (06)
- **Coordinate Fuzzing**: Feature mechanics (02) → HMAC algorithm + schema (05) → Anti-triangulation (06)
- **Age Gate (18+ only)**: Feature (02) → UX flow (03) → Verification + anti-bypass blocklist (06)
- **Block Feature**: Feature (02) → Schema (05) → Moderation interaction (06)
- **Search**: Feature (02) → PostgreSQL FTS schema (05)
- **Notifications (DB-persisted)**: Feature (02) → Schema (05) → Push delivery (04)
- **Suspension vs Ban**: Admin actions (07) → `users.suspended_until` schema (05) → Daily unban worker (05)
- **Premium Username Customization**: Freemium tier + feature-flag kill switch (01) → Feature rules (02) → UX + entry point + cooldown copy (03) → `username_history` schema + PATCH endpoint + transaction flow (05) → Moderation hook (`username_flagged`) + 30-day release hold rationale (06) → Admin oversight tools (07) → Phase 4 build + tests (08)
- **Reserved Usernames**: Feature mention (02) → Schema with `source` column (05) → Admin editor with `seed_system` immutability (07) → Pre-Phase 1 seed + Phase 3.5 editor build + CI lint (08)
- **Free-Tier Like Cap (10/day)**: Freemium tier (01) → Social features (02) → UX modal copy (03) → Layer 2 rate limit table (05) → `premium_like_cap_override` feature flag for server-side cap adjustment without mobile release (05) + Decision 28 (08)
- **Content Length Limits (post/reply 280)**: Freemium table + rationale (01) → Feature spec (02) → Input validation middleware + schemas (05) → Account security listing (06) → Pre-Launch content length guard tests (08)
- **Three-Environment Deployment (dev/staging/production)**: Cost row in forecast (01) → Deployment section with config separation + subdomain map + secret namespacing + CI/CD flow + mobile build flavors (04) → Pre-Phase 1 staging bootstrap + mobile flavor verification + Pre-Launch env separation tests (08)
- **CSAM Detection Trigger Path**: Feature (02) → Architecture trigger-path rationale with no-webhook-from-CF clarification (04) → Media Moderation + Internal Endpoint Security exceptions (06) → CSAM archive schema with dedup UNIQUE constraints + `source` column + admin-trigger flow (05) → Admin Panel CSAM Log Viewer + admin-triggered handler invocation (07) → Phase 4 build + Pre-Launch CSAM E2E test + dedup test (08)
- **Cloudflare-Fronted IP Extraction**: Architecture client-IP origin note (04) → Implementation middleware + spoof protection + Cloud Armor allowlist (05) → Pre-Phase 1 ingress enforcement + CI lint rule forbidding raw XFF reads (08)
- **Content Moderation Keyword Lists**: Text moderation pointer (06) → Implementation storage + 4-step `ModerationListLoader` fallback order (Remote Config → repo file → Secret Manager slot) + Aho-Corasick matcher (05) → Admin Panel Feature Flag Admin covers the keyword parameters (07) → Pre-Phase 1 keyword-list bootstrap (08)
- **Referral Ticket Creation**: Mechanism description in business terms (01) → Signup-time endpoint flow + invite-code HMAC format + **O(1) resolution via `users.invite_code_prefix UNIQUE` index** + daily activity-check worker (05) → Phase 4 ticket-creation build + Pre-Launch test (08)
- **Privacy Downgrade 72h Flip**: UX banner + countdown (03) → Product flow (02) → `users.privacy_flip_scheduled_at` column + idempotent webhook handler + hourly worker + cancellation path (05) → Admin Panel Privacy Flip Monitor (07) → Phase 4 build + Pre-Launch test (08)
- **Apple S2S Deletion Flows**: onboarding disclosure (03) → dual `deletion_requests.source` values + immediate-execution path for `account-delete` + 30-day grace for `consent-revoked` (05) → Security + Apple S2S Notification Handling (06) → Phase 4 build + Pre-Launch test (08)
- **Admin Session Cookie + CSRF**: Architecture Admin Session Mechanism + three-role DB separation (04) → `admin_sessions` schema with `csrf_token_hash` + `admin_webauthn_challenges` schema + full cookie mechanism (05) → Operations Layer 2 auth + CSRF verification (07) → Pre-Phase 1 documentation + Pre-Launch cookie/CSRF/replay tests (08)
- **Chat Message Redaction**: Chat spec + redaction UX copy (03) → `chat_messages.redacted_by` column + atomicity CHECK + `chat_message_redacted` notification (05) → Admin Panel Chat Message Redaction feature (07) → Phase 4 build + Pre-Launch test (08)
- **Notifications Type Catalog**: feature spec (02) → `notifications` schema expanded CHECK + per-type body_data catalog (05) → UX rendering strings (03) → Pre-Launch notification enum test (08)

## Terminology

- **Dev Phase**: Pre-Phase 1, Phase 1, 2, 3, 3.5, 4, Pre-Launch, Public Launch. Maps to the 19-20 week pre-launch build.
- **Month N**: Calendar month post Public Launch (Month 1 = Public Launch month).
- **Pre-swap period**: Month 1 through Month 14, before the chat realtime swap from Supabase Broadcast to Ktor WebSocket + Redis Streams.
- **Post-swap**: Month 15+.
- **Solo admin period**: Period before the second admin hire. Authentication = email + password + TOTP mandatory.
- **Multi-admin period**: From the second admin onward. WebAuthn mandatory.
