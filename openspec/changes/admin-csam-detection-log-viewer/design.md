## Context

PR [#358](https://github.com/aditrioka/nearyou-id/pull/358) (`csam-detection-webhook-and-archive`, merge-ready) shipped the CSAM takedown backend:

- **`V29 csam_detection_archive`** — one legal-preservation row per matched image. Plaintext Kominfo essentials (`image_hash UNIQUE`, `cf_match_id`, `ncmec_reference`), `source CHECK ('admin_manual','cf_worker')`, AES-256-GCM `encrypted_metadata BYTEA` (nullable, fail-soft), `kominfo_report_id`/`kominfo_reported_at` (NULL = pending), `created_at`, `expires_at = created_at + 90d`. No FK to `users` (survives hard-delete). Indexes: `cf_match_id` partial-UNIQUE; purge index on `expires_at WHERE kominfo_reported_at IS NOT NULL`.
- **`CsamDetectionService.handleDetection(Input)`** — the fixed-policy atomic takedown (resolve uploader → tombstone post → permanent-ban + `token_version` bump → cascade-tombstone → AES-GCM archive → `moderation_queue csam_detected` → audit). Idempotent; ledger-miss-resilient (archive-only). `Input.source: Source` (`ADMIN_MANUAL`/`CF_WORKER`), `Input.actorAdminId: UUID`.
- **`POST /internal/csam-webhook`** — two auth paths: admin-session cookie + `X-CSRF-Token` gated to `owner`/`admin` (reusing `SessionRepository`/`AdminUserRepository`/`AdminRoleGate`/`HashUtil`), or CF-Worker Bearer + HMAC. Body `{image_id, image_hash, cf_match_id?, ncmec_reference?}`; both `image_id` and `image_hash` mandatory. Returns JSON `{status: actioned|archived}`.
- **`CsamRepository`** (archive/resolve/purge), **`CsamMetadataEncryptor`** (AES-256-GCM, AAD-bound to `image_hash`), **`AdminAuditLogger`**.

This change is the operator front-end #358 explicitly anticipated. #358's `CsamWebhookRoutes` docstring: the admin-session path's "*production caller (the admin paste-URL form) ships with the deferred admin viewer; this change ships + tests the auth contract*." The V29 migration: `kominfo_report_id` is "*set later by the deferred admin review surface.*" Both = this change.

**Constraints:** no Flyway migration (existing V29 columns only); hard dependency on #358 (apply sequences behind its squash-merge); admin UI per docs/11 §3.6 (Pebble + HTMX + vendored CSS, mockup frame f13); image bytes never enter the panel; reuse #358's service/repo/encryptor (no fork).

## Goals / Non-Goals

**Goals:**
- An operator can, from the Admin Panel after CF's daily email, **view** detected events, **invoke** the fixed-policy takedown (MVP path), **record** the Kominfo filing (releasing the row to the purge worker), and **decrypt** archived metadata on demand — every destructive/sensitive action `owner`/`admin`-gated, CSRF-protected, and audit-logged.
- Make the <24h Kominfo reporting obligation (docs/06) operable end-to-end.
- Stay strictly additive: reuse #358's seam; introduce no new pattern; no migration.

**Non-Goals:**
- The Cloudflare Worker auto-forward path (Phase 2+; already supported by #358's webhook). This change is the admin-manual MVP front-end only.
- Changing the takedown policy, the archive schema, or the purge worker behavior.
- An internal CSAM unblock-decision workflow (CF owns the unblock decision; the panel only surfaces CF's review path).
- Rendering image content in any form (by construction).

## Decisions

### D1 — Admin takedown invocation: **in-process `CsamDetectionService` call**, not an HTTP self-call to `/internal/csam-webhook` (central decision)

The admin "Invoke handler" form (mockup f13) submits to a new admin-subtree route `POST /admin/csam/takedown`, which calls `CsamDetectionService.handleDetection(Input(source = ADMIN_MANUAL, actorAdminId = <admin-panel session's admin id>, …))` **directly in-process** and renders the `actioned`/`archived` result as an HTML fragment.

- **Alternative considered — POST to #358's `/internal/csam-webhook` admin-session path.** This is what the f13 caption ("`POST /internal/csam-webhook` (via panel)") and #358's docstring literally suggest. Rejected as the panel's mechanism because: (a) it is an **HTTP self-call** — a routes→routes loop that violates docs/11 §3.1 layering (routes → service → repository, never a route calling another route over HTTP); (b) the endpoint returns **JSON**, not an HTML fragment, breaking the HTMX/no-JS panel UX (§3.6); (c) it would re-derive the acting admin by re-parsing the `__Host-admin_session` cookie, when the admin-panel auth plugin already has the authenticated admin in context.
- **Why in-process wins:** the admin route already holds the authenticated `owner`/`admin` session (so `actorAdminId` and the role gate are local), the service is the canonical transaction boundary that already does the atomic takedown + audit + idempotency, and the route returns a proper HTML fragment.
- **Reconciliation with #358 (explicit):** #358's webhook admin-session auth path is **kept and stays tested** — it is the documented *external* admin-authenticated contract (e.g. a future direct API caller / ops tool) and shares the exact same `CsamDetectionService.handleDetection` sink, so behavior is identical. This change does **not** delete or weaken those tests; it realizes "via panel" as the layering-correct in-process call rather than an HTTP round-trip. The f13 caption is interpreted at the behavior level (the panel triggers the same handler), not as a literal "the browser POSTs to `/internal/...`". Per docs/11 §3.6 precedence, specs/docs govern behavior and the board governs look.
- **Best-practice verification (verified 2026-06-20 via WebSearch):** for a **modular monolith** (nearyou-id is "one Ktor deployable"), the canonical pattern is for a server-side route to call the shared **service layer in-process**, not to make an HTTP self-call to its own internal endpoint — "internal function calls happen in nanoseconds, while a REST hop burns multiple milliseconds … avoids network serialization and separate connection pools" ([sufficiently-advanced.technology, modular-monolith](https://sufficiently-advanced.technology/post/modular-monolith-part-ii)); same-process synchronous HTTP ("chatty service") is a recognized anti-pattern ([aimconsulting](https://aimconsulting.com/insights/chatty-service-anti-pattern-explained/), [Microsoft Learn — microservice communication](https://learn.microsoft.com/en-us/dotnet/architecture/microservices/architect-microservice-container-applications/communication-in-microservice-architecture)). This confirms D1; docs/07 §56 + the mockup f13 caption ("calls `/internal/csam-webhook` internally") are DESIGN-era wording reconciled by a `follow-up` issue (do not rewrite docs in this change).

### D2 — Capability boundary: new `admin-csam-detection-log`, `csam-detection` unchanged

The viewer/takedown-trigger/Kominfo/decrypt surface is new admin behavior → new capability spec. The shipped `csam-detection` capability (handler + archive + purge) is **not modified**: the takedown reuses `handleDetection` without changing its contract, and the purge worker already holds on `kominfo_reported_at IS NULL` — so setting that column is *new* behavior here, not a change to the purge requirement. Avoids an archive-time shared-spec conflict with #358.

### D3 — Image-content-free by construction

The panel reads only scalar columns (`image_hash`, `source`, `kominfo_*`, timestamps) and, on explicit decrypt, the JSON metadata blob (uploader id, post id, source, cascade count — no bytes; #358's encryptor never stores image bytes). There is **no** code path that fetches, proxies, or renders the image or its CF URL. A test asserts the viewer/decrypt responses contain no image-URL/`img.nearyou.id` render. The pasted `image_id` in the takedown form is an opaque CF identifier, never resolved to a renderable URL in the panel.

### D4 — Decrypt-on-view is a distinct audited write action

Decryption is `owner`/`admin`-only and **every** decrypt writes an `admin_actions_log` `csam_metadata_decrypted` row (docs/06 §223), surfacing the plaintext metadata as an HTMX fragment swapped into the row — it is not eagerly decrypted in the list query (which would be unauditable and bulk-exposing). Fail-soft: when `csam-archive-aes-key` is unset (pre-launch) or the row's `encrypted_metadata` is NULL, the fragment shows "metadata unavailable (key unprovisioned)" and still writes the audit row (the *attempt* is logged) — never a 500.

### D5 — Rate-limit placement

- **Takedown** → the shared **20/hour destructive budget** (the existing admin destructive limiter). It is a destructive/punitive action (ban + cascade-delete), so it belongs with suspend/ban/redact, not on a restorative counter.
- **Kominfo-report write** and **metadata decrypt** → **dedicated per-admin counters off the audit-trail-as-ledger limiter** (the `admin-rejected-identifiers-clear-action` / `admin-subscription-grace-monitor` precedent), proposed at **30/admin/hour** each. These are bookkeeping/forensic, not user-punitive, so they must not consume the destructive budget. Final caps confirmed in tasks.

### D6 — Kominfo write releases the row to the existing purge worker

`POST /admin/csam/{id}/kominfo-report` sets `kominfo_report_id` + `kominfo_reported_at = now()` in one transaction with its audit row. The shipped daily purge (`DELETE … WHERE expires_at < NOW() AND kominfo_reported_at IS NOT NULL`) then becomes eligible to erase the row after its 90-day deadline — i.e. recording the filing is what *ends* the preservation hold. Idempotent: re-filing an already-filed row is rejected (no mutation, no audit row) so the original `kominfo_reported_at` (the legal timestamp) is never overwritten.

### D7 — Standards conformance (anti-patchwork; docs/11 Pattern Registry)

This change builds on existing patterns and introduces **no new pattern** (no docs/11 § Pattern Registry amendment required):

- **Backend layering (§3.1):** admin route → `CsamDetectionService`/`CsamRepository` → DB. Repository extended in place (list/filter query, `kominfo` update, decrypt accessor) — no second repository.
- **Admin viewer (read-only keyset + HTMX fragment + plain-`GET` fallback):** mirrors `admin-privacy-flip-monitor` + `admin-subscription-grace-monitor`.
- **Admin write action + CSRF + `owner`/`admin` gate + dedicated audit-trail rate counter + immutable `admin_actions_log` row:** mirrors `admin-hard-delete-queue` (expedite) + `admin-rejected-identifiers-clear-action`.
- **Admin UI substrate (§3.6):** Pebble templates + HTMX swaps + no-JS fallback + vendored vanilla CSS (tokens from the board); mockup frame **f13** is the canonical visual target (measurement annex generated at apply).
- **Invariants:** raw `csam_detection_archive` reads are admin-module-sanctioned; the takedown's user/post mutations go through #358's already-lint-clean service; no `display_location`/block-join concerns (this surface touches neither timelines nor posts directly).

### D8 — Unblock-request surfacing is minimal

For `cf_worker` rows the panel surfaces the CF-provided review path (link-out) + a status indicator. No internal state machine — CF owns the unblock decision. Kept in-scope (it is in f13 + docs/07) but deliberately thin.

## Risks / Trade-offs

- **[D1 reinterprets #358's literal "POST /internal/csam-webhook (via panel)".]** → Mitigation: same handler sink, identical behavior, #358's webhook admin path kept + tested; decision documented here and flagged as the #1 review item for the security-and-invariant + general lenses.
- **[Sequencing: apply before #358 merges would not compile.]** → Mitigation: proposal authored against the shipped contract now; `/opsx:apply` bases on post-#358 `main`; tasks.md Phase 0 gates on "#358 merged".
- **[Operator pastes a wrong `image_id`/`image_hash`.]** → Mitigation: the handler is idempotent and ledger-miss-resilient (archive-only when the image has no `image_uploads` row); the form requires both fields (per the shipped contract) + an `hx-confirm` destructive guard; the action is audit-logged regardless.
- **[Decrypt exposes uploader PII to a broad admin set.]** → Mitigation: `owner`/`admin`-only, per-decrypt audit row, fail-soft, and never bulk — one row at a time on explicit action.
- **[Editing `admin/static/admin.css` silently breaks CI integrity check.]** → Mitigation: tasks.md notes the SHA256 re-pin step (it is a CI lint-lane bash step the local gradle gate misses).

## Migration Plan

- **No DB migration.** Deploy is code-only on top of post-#358 `main`.
- **Sequencing:** merge #358 first; rebase this branch onto `main`; implement; pre-archive admin-surface bring-up (verify-loop on local Ktor boot) + screenshot evidence; staging branch deploy + admin smoke (unauthenticated `GET /admin/csam` → 302 `/admin/login` confirms route mounted + gated); squash-merge.
- **Rollback:** revert the squash commit — no schema state to unwind; the `csam_detection_archive` table and webhook (from #358) are unaffected.

## Open Questions

- **Exact dedicated rate-limit caps** for Kominfo-write + decrypt (proposed 30/admin/hour each) — confirm against the audit-trail limiter precedents at apply (not behavior-affecting; safe to finalize in tasks).
- **D1 — RESOLVED 2026-06-20.** Surfaced to the operator, who asked for a best-practice check; the WebSearch verdict (above) confirms the in-process service call for a modular monolith. docs/07 §56 + mockup f13 caption reconciliation tracked as a `follow-up` issue. No longer open.
