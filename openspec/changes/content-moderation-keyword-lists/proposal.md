# content-moderation-keyword-lists

## Why

Posts, replies, and chat messages currently ship with **no text moderation gate** — the keyword-blocklist (Layer 1) and UU ITE wordlist (Layer 2) tiers documented in [`docs/06-Security-Privacy.md:153`](../../../docs/06-Security-Privacy.md) are unbuilt. The supporting scaffolding is in place but inert: [`docs/05-Implementation.md:1253`](../../../docs/05-Implementation.md) marks "Content Moderation Keyword Lists" as `Status: DESIGN` with a "Future proposal will define" hook; [`openspec/specs/moderation-queue/spec.md:14`](../../specs/moderation-queue/spec.md) reserves the `uu_ite_keyword_match` trigger enum value at V9 with no writer; the Remote Config keys (`moderation_profanity_list`, `moderation_uu_ite_list`, `moderation_match_threshold`) and Secret Manager slot (`content-moderation-fallback-list`) are reserved with no Kotlin consumer; the repo-committed fallback files at `backend/ktor/src/main/resources/moderation/profanity.default.txt` + `uu_ite.default.txt` (per Pre-Phase 1 §36) do not exist. This change builds the foundational text moderation pipeline so every text-write path runs against a deterministic keyword pipeline before reaching the database — closing a Pre-Launch security-review gap and establishing the matcher contract that the upcoming Perspective API change (Phase 2 §16) and the Premium username customization change will both consume.

## What Changes

- **NEW** `ModerationListLoader` with the deterministic 4-step fallback ladder canonicalized at [`docs/05-Implementation.md:1255`](../../../docs/05-Implementation.md): Redis 5-min cache → Firebase Remote Config → repo-committed `*.default.txt` resource files → GCP Secret Manager `content-moderation-fallback-list` last-resort. Every fallback step emits a Sentry WARN with the tier name + reason, so silent degradation cannot occur.
- **NEW** Pure-Kotlin Aho-Corasick `KeywordMatcher` engine that takes a list of keywords + threshold, returns a structured match result `{matchedKeywords: List<String>, matchCount: Int}`. Case-insensitive, word-boundary-aware. Reusable across Layer 1 (profanity), Layer 2 (UU ITE), and the future Premium-username-customization writer.
- **NEW** `TextModerator` orchestrator: combines loader + two matcher instances (one per list), exposes `moderate(content: String): ModerationVerdict` returning either `Allow`, `Reject(reason: ProfanityHit)`, or `Flag(uuIteHit: UuIteKeywordHit)`. Decision rules per [`docs/06-Security-Privacy.md:155–159`](../../../docs/06-Security-Privacy.md):
  - **Layer 1 — profanity blocklist**: any single match (1 ≥ keyword) ⇒ `Reject` (sync 4xx). Pre-curated list, low ambiguity, REJECT is cleaner UX than ghost-publish-then-hide. **D1 resolution: REJECT** (see design.md). This means moderation-queue does NOT need a new trigger enum (D2 follows D1).
  - **Layer 2 — UU ITE wordlist**: matches reaching `moderation_match_threshold` (default 3) ⇒ `Flag` (200 success + `moderation_queue` row with `trigger = 'uu_ite_keyword_match'`, no auto-hide). Per [`docs/06-Security-Privacy.md:158`](../../../docs/06-Security-Privacy.md) "Higher threshold: 1 match = soft flag to the moderation queue (not auto-hide)" — the docs say "1 match" but `moderation_match_threshold` Remote Config default is 3 per Pre-Phase 1 §36; the proposal honors the Remote Config parameter (3) over the literal docs phrase ("1 match") and surfaces this canonical-doc reconciliation in design.md D5 + a `FOLLOW_UPS.md` entry to amend `docs/06-Security-Privacy.md:158`.
- **NEW** Repo-committed fallback files at `backend/ktor/src/main/resources/moderation/profanity.default.txt` and `uu_ite.default.txt`, each shipping with placeholder sentinels (e.g., `__seed_profanity_placeholder__`) — the actual operational wordlists live in Firebase Remote Config; the repo files are fail-soft fallbacks. Operations work expands the seeds via Remote Config; this change ships only the file scaffolding + the placeholder sentinels per `### Requirement: Repo-committed default fallback wordlists exist`.
- **MODIFIED** `post-creation` capability: `POST /api/v1/posts` runs the moderator gate AFTER existing length validation + block check, BEFORE INSERT. `Reject` ⇒ HTTP 400 with `code = "content_moderated_profanity"` + Bahasa Indonesia message. `Flag` ⇒ INSERT proceeds + `moderation_queue` row inserted in the same transaction.
- **MODIFIED** `post-replies` capability: `POST /api/v1/posts/{post_id}/replies` runs the same gate; same REJECT / FLAG semantics; queue row uses `target_type = 'reply'`.
- **MODIFIED** `chat-conversations` capability: `POST /api/v1/chat/{conversation_id}/messages` runs the same gate, AFTER existing block check + length validation, BEFORE the broadcast publish path. `Flag` queue row uses `target_type = 'chat_message'`.
- **NEW** Detekt rule `ContentWriteRequiresModerationRule`: detect handlers that write user-supplied content to `posts.content` / `post_replies.content` / `chat_messages.content` without going through `TextModerator.moderate(...)`. Annotation `// @allow-content-write-without-moderation: <reason>` for sanctioned exceptions (admin overrides, internal seed, etc.). Reserved follow-up if the writer surface ends up too thin to justify the rule.

## Capabilities

### New Capabilities

- `content-moderation-keyword-lists`: the `ModerationListLoader` + `KeywordMatcher` + `TextModerator` stack — the matcher contract, fallback ladder behavior, threshold semantics, integration boundary, fail-closed-vs-fail-open posture, and Sentry observability surface.

### Modified Capabilities

- `post-creation`: adds a text-moderation gate to the `POST /api/v1/posts` pipeline; `Reject` → 4xx, `Flag` → 200 success + queue row.
- `post-replies`: adds the same gate to `POST /api/v1/posts/{post_id}/replies`.
- `chat-conversations`: adds the same gate to `POST /api/v1/chat/{conversation_id}/messages`, before the broadcast publish.

(`moderation-queue` is **NOT** modified by this change. D1 resolves to REJECT, so no `profanity_keyword_match` enum value is needed; the existing `uu_ite_keyword_match` trigger covers the Layer 2 writer.)

## Impact

**Code**:
- `KeywordMatcher` (Aho-Corasick) lands in `:core:domain` — pure Kotlin, no vendor deps, no new module needed.
- New `:infra:remote-config` Gradle module scaffolded under `infra/remote-config/` (resolves the "DECISION NEEDED" status flagged for this module in [`openspec/project.md`](../../project.md) § Module Structure — this change is the first concrete platform-wide-config consumer; the `no vendor SDK import outside :infra:*` rule mandates the Firebase Remote Config Admin SDK live in an `:infra:*` module). Owns `RemoteConfigClient`, initializes a named FirebaseApp `"nearyou-rc"` from `secretKey(env, "firebase-admin-sa")`. Reuses the Firebase Admin SDK BOM `9.5.0` already pinned in [`gradle/libs.versions.toml`](../../../gradle/libs.versions.toml) by `fcm-push-dispatch` — no new third-party version.
- New code in `:backend:ktor` for `ModerationListLoader` + `TextModerator` (Redis + resource file + Secret Manager surfaces, plus `RemoteConfigClient` injection from `:infra:remote-config`).
- Modifications to `PostService`, `PostReplyService`, `ChatMessageService` (or wherever the canonical write paths live in `:backend:ktor`) to invoke `TextModerator.moderate(...)` and short-circuit on `Reject`.
- New repo-committed resource files: `backend/ktor/src/main/resources/moderation/profanity.default.txt`, `uu_ite.default.txt`.
- New Detekt rule in `:lint:detekt-rules` (optional, may defer).
- Tests: matcher correctness (positive + negative + threshold-boundary), loader fallback ladder (each tier failing), Sentry-WARN-per-fallback, integration with each write path (REJECT vs FLAG behavior), repo-fallback file integrity, Firebase-import-boundary scan for `:backend:ktor` source.

**APIs**:
- `POST /api/v1/posts` — new error response `400 { "code": "content_moderated_profanity", "message": "..." }`.
- `POST /api/v1/posts/{post_id}/replies` — same error response.
- `POST /api/v1/chat/{conversation_id}/messages` — same error response.
- No change to GET / DELETE endpoints.

**Dependencies**:
- New direct dependency on an Aho-Corasick library OR pure-Kotlin in-house implementation. Design.md D3 resolves (lean toward in-house — Aho-Corasick is ~150 LOC and avoids the dependency).
- Reuses existing Redis infrastructure (`:infra:redis`) for the cache tier — no new infra.
- Reuses existing Secret Manager helper (`secretKey(env, "content-moderation-fallback-list")`) — no new infra.
- Reuses existing Sentry surface — no new infra.

**Systems**:
- `moderation_queue` writer count grows from 1 (auto-hide-3-reports) to 2 (adds Layer 2 UU ITE writer). Admin-triage volume increase expected to be modest given the threshold (default 3 matches required); operational tuning via the Remote Config `moderation_match_threshold` parameter without a redeploy.
- Pre-Launch security-review checklist item closes for the keyword-blocklist tier; Layer 3 (Perspective API) remains a separate Phase 2 §16 follow-up change.
- Phase 2 follow-ups unblocked by this change (deferred):
  - Perspective API integration (consumes the same `TextModerator` boundary, adds a `Reject`/`Flag` async layer)
  - Premium username customization (consumes `KeywordMatcher` + writes `trigger = 'username_flagged'`)
  - Admin Panel keyword-list editor (Phase 3.5 admin work)
- `FOLLOW_UPS.md` entry: amend `docs/06-Security-Privacy.md:158` to align "1 match" phrasing with the Remote Config `moderation_match_threshold` (default 3) — see design.md D5.
