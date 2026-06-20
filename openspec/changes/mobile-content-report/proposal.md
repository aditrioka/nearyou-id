## Why

Mobile users can report a **user** (the profile "Laporkan" kebab, shipped in `mobile-profile` [#245](https://github.com/aditrioka/nearyou-id/pull/245)) but cannot report an individual **post** or **reply** — even though [`docs/03-UX-Design.md` §Report UX:230](../../../docs/03-UX-Design.md) ("Kebab menu (post, reply, profile page): 'Laporkan'") and [`docs/06-Security-Privacy.md` §Report System:229](../../../docs/06-Security-Privacy.md) ("One-tap report from a post, reply, profile, and chat message") both specify post + reply reporting. For an 18+ user-generated-content app this is a **launch-blocking store-compliance and safety gap**: Google Play's UGC policy and App Store Guideline 1.2 both require a per-content reporting mechanism. The backend is already fully shipped — `POST /api/v1/reports` accepts `target_type` of `post`/`reply` with a 10/hour rate limit, `409 duplicate_report`, and auto-hide-on-3-distinct-reporters (the `reports` capability, V9) — so this is a **mobile-only, zero-backend** change that reuses the shipped report enum, outcome model, and dialog from the profile surface.

## What Changes

- **Report a post from `PostDetailScreen`.** Add a "Laporkan" affordance to the post header, shown only for posts the viewer does **not** author (server-authoritative `isAuthor` — mirrors the existing Edit affordance gate). Tapping opens the shared report dialog; submission posts `target_type = "post"`, `target_id = <post id>`.
- **Report a reply from `PostDetailScreen`.** Each reply row gains a "Laporkan" affordance; submission posts `target_type = "reply"`, `target_id = <reply id>`. PII discipline is preserved — reply rows already drop `author_id` (the `mobile-post-detail` contract); the report sends only the reply id, exposing no new PII.
- **Shared report dialog (reuse + extract).** The reason picker (six user-facing categories — Spam, Ujaran kebencian (SARA), Pelecehan, Konten dewasa, Misinformasi, Lainnya) + optional 200-char note (placeholder "Jelaskan lebih detail jika perlu") is the SHIPPED profile report dialog, **extracted from `profile/` into a shared location** (`data/report/` + `ui/components/`) and consumed by both profile and post-detail. This is a deliberate pattern-consolidation (one report-submission pattern, not two — the anti-patchwork rule).
- **Outcome handling** via the shipped sealed `ReportOutcome`: `Submitted` AND `Duplicate` (409) → the success toast "Laporan terkirim. Tim moderasi akan meninjau." ([`docs/03`:233](../../../docs/03-UX-Design.md); duplicate maps to the same toast so a reporter learns nothing about prior reports — prevents retaliation/enumeration); `RateLimited` (429) → a rate-limit message; `NetworkError` → retryable. The reporter never sees the review outcome ([`docs/03`:234](../../../docs/03-UX-Design.md)).
- **Explicit deferrals** (each a positive requirement + negative-guard scenario + tracking note, so the follow-up has a MODIFY hook):
  - **Timeline-card (`PostCard`) report kebab** — deferred behind `image-attached-posts` ([#354](https://github.com/aditrioka/nearyou-id/pull/354)), which currently owns the shared `PostCard`. This change does **not** touch `PostCard` or the `mobile-post-card` spec (keeps the footprint disjoint for parallel squash-merge). Tracked as a `follow-up` issue.
  - **Chat-message report** (`target_type = "chat_message"`) — a separate chat-surface change.
  - **Self-report guard for replies** — the backend `self_report_rejected` fires only for `target_type = "user"`; reporting one's own reply would succeed (harmless, rare; the client cannot detect own-authorship since `author_id` is intentionally dropped). Accepted as-is in v1.
- **No backend change.** The reports endpoint, rate limit, duplicate handling, and auto-hide are all shipped.

## Capabilities

### New Capabilities
- `mobile-content-report`: the mobile surface for reporting an individual post or reply — the shared report dialog (reason picker + optional note), submission against `POST /api/v1/reports` with `target_type` post/reply, and the sealed `ReportOutcome` → UI-state mapping (success toast, duplicate-as-success, rate-limit message, retryable network error). Owns the extracted shared report seam.

### Modified Capabilities
- `mobile-post-detail`: the post header gains a non-authored-post report affordance; each reply row gains a report affordance. The screen wires the shared `mobile-content-report` dialog + submission.

> `mobile-profile` is **not** listed here — it is touched at the implementation level only (mechanically migrated to import the extracted shared report seam), with **no requirement-level behavior change** (the profile report UX and wire contract are unchanged), so it needs no spec delta. It appears under § Impact.

## Impact

- **Module**: `:mobile:app` only (commonMain + androidUnitTest). No `:backend:ktor`, no Flyway migration, no `:infra:*`, no `libs.versions.toml`.
- **Code**: new `data/report/` (extracted `ReportReasonCategory`, `ReportOutcome`, report ApiClient/Repository seam) + `ui/components/` report dialog; `PostDetailScreen` + `PostDetailViewModel` (report affordances, dialog wiring, one-shot toast state); `ProfileScreen`/`ProfileViewModel`/`profile/` updated to import the relocated seam.
- **Wire/API**: consumes the shipped `POST /api/v1/reports` (`reports` capability) — no new or changed endpoint.
- **Disjointness**: the hard-disjoint guarantee is on the shared timeline card — this change never touches `PostCard` or the `mobile-post-card` spec, both of which `image-attached-posts` (#354) modifies. #354 *also* modifies `mobile-post-detail` + `PostDetailScreen` (to render the attached image + add an `imageUrl` route field); that overlap is at the *file* level only — #354's post-detail requirements (image render, route payload) and this change's (report affordances, the RENAMED block-deferral) are **disjoint requirements**, so both spec deltas archive cleanly and the `PostDetailScreen.kt` edits are different regions (mechanical rebase for whichever merges second). No overlap with `csam-detection-webhook-and-archive` (#358), `account-data-export` (#356), `admin-hard-delete-queue` (#355), or `referral-grant-worker` (#353).
- **Tests**: `commonTest` (enum `toWire` mapping, ViewModel outcome→state mapping, one-shot clear) + Robolectric `PostDetailScreenTest` additions (report affordance shown on non-authored post / hidden on own post, per-reply affordance, dialog submit → toast); new screen tests added to the Release-variant exclude.
