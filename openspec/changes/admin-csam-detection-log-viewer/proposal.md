## Why

The CSAM takedown backend shipped ([`csam-detection-webhook-and-archive`](https://github.com/aditrioka/nearyou-id/pull/358), V29 `csam_detection_archive` + `POST /internal/csam-webhook` + `CsamDetectionService`) but ships **no operator UI**. The project's documented primary takedown path is "*admin-triggered from the Admin Panel after CF's daily email*" ([docs/04](../../../docs/04-Architecture.md) § CSAM trigger path) — yet there is no screen to view detected events, invoke a takedown, or record the mandatory Kominfo filing, so an operator today would have to hand-craft an authenticated HTTP call. #358 itself anchors this work: it built and tested the webhook's admin-session auth path "*for an admin caller that ships with the deferred admin viewer*," and the V29 `kominfo_report_id`/`kominfo_reported_at` columns are documented as "*set later by the deferred admin review surface*" — both referring to this change. CSAM handling is a **hard Pre-Launch security-review gate** with a <24h Kominfo reporting obligation ([docs/06](../../../docs/06-Security-Privacy.md) § Kominfo Reporting Obligation); the front-end that makes that obligation operable is the highest-value remaining child-safety item.

## What Changes

- **New `GET /admin/csam` detection-log viewer** — read-only, keyset-paginated (newest-first over `(created_at, id)`) list of `csam_detection_archive` rows; composable filters: `source` (`cf_worker`/`admin_manual`), Kominfo status (filed vs pending, via `kominfo_reported_at IS [NOT] NULL`), UTC `from`–`to` date range; a pending-count summary banner (the same-business-day SOP signal); HTML-escaped HTMX fragment render + plain-`GET` fallback; any authenticated admin role may read. **Hard invariant: image bytes are NEVER rendered** — only `image_hash` and (on-demand, audit-logged) decrypted metadata. Served by the existing V29 indexes — **no new index, no migration**.
- **Admin-triggered takedown (the MVP path)** — a paste-`image_id`+`image_hash` (from CF's email) form invokes the shipped fixed-policy handler via `CsamDetectionService.handleDetection(source = ADMIN_MANUAL, actorAdminId = <session admin>)` **in-process** (layering-correct; the service already runs the atomic takedown + writes its own audit row + is idempotent). `owner`/`admin` role + CSRF **only**; a read-only/non-owner-admin session is rejected (403). Returns the `actioned`/`archived` result as an HTML fragment.
- **`POST /admin/csam/{id}/kominfo-report`** — record `kominfo_report_id` + set `kominfo_reported_at = now()` on the archive row; `owner`/`admin` + CSRF + required non-blank report id; one immutable `admin_actions_log` row (`csam_kominfo_reported`); idempotent on already-filed/unknown id. This write is what **releases the row to the existing daily purge worker** (which holds while `kominfo_reported_at IS NULL`).
- **Audit-logged metadata decrypt-on-view** — reveal the AES-256-GCM `encrypted_metadata` only via the admin-only helper (`csam-archive-aes-key`); `owner`/`admin` only; **every** decrypt writes an `admin_actions_log` row ([docs/06](../../../docs/06-Security-Privacy.md) § CSAM Archive Encryption); fail-soft (graceful "key unavailable" message) when the key is unprovisioned — never crashes.
- **Minimal unblock-request surfacing** — for `cf_worker` rows, surface the Cloudflare-provided review path/status (link-out + indicator); no internal workflow (CF owns the unblock decision).
- **No Flyway migration.** Reads/writes existing V29 columns; extends `CsamRepository` (list/search query + Kominfo update + decrypt accessor) and reuses `CsamDetectionService` / `CsamMetadataEncryptor` / `AdminAuditLogger` — no second repository, no new pattern.

## Capabilities

### New Capabilities
- `admin-csam-detection-log`: the Admin Panel CSAM surface — detection-log viewer, admin-triggered takedown invocation (MVP "paste from CF email" path), Kominfo-report tracking, audit-logged metadata decrypt, and unblock-request surfacing, all `owner`/`admin`-gated where destructive and image-content-free by construction.

### Modified Capabilities
<!-- None. The takedown reuses the shipped `csam-detection` handler without changing its spec; the purge worker already holds on `kominfo_reported_at IS NULL`, so setting that column is new behavior in the new capability, not a modification of `csam-detection`. -->
- _(none)_

## Impact

- **New backend code** (`:backend:ktor`, `admin` package + reuse of `moderation/csam`): admin route group `GET /admin/csam`, `POST /admin/csam/takedown`, `POST /admin/csam/{id}/kominfo-report`, `POST /admin/csam/{id}/decrypt`; a Pebble template + admin-nav entry under "Anti-abuse" (mockup frame f13); `CsamRepository` extensions (list/filter query, Kominfo update, decrypt accessor).
- **Reuses, does not fork**: `CsamDetectionService`, `CsamMetadataEncryptor`, `AdminAuditLogger`, and the admin-auth/CSRF/role-gate seam — all shipped by #358 + the admin scaffold.
- **New `admin_actions_log` `action_type`s**: `csam_kominfo_reported`, `csam_metadata_decrypted` (the takedown is already audited by `CsamDetectionService`).
- **Rate limits**: takedown counts against the shared 20/hour destructive budget (it is destructive/punitive); Kominfo-report write + metadata decrypt get dedicated low per-admin counters off the audit-trail limiter (bookkeeping/restorative, not the destructive cap).
- **No migration**, no `libs.versions.toml` change, no new module. If `admin/static/admin.css` is touched, the CI static-asset SHA256 integrity check must be re-pinned.
- **Dependency / sequencing**: hard-depends on #358 (the `csam_detection_archive` table + `CsamDetectionService` + admin-auth reuse). The proposal is authored now against the shipped contract; **`/opsx:apply` sequences behind #358's squash-merge to `main`** (base the implementation on post-#358 `main`).
- **UI-affecting** (admin panel) → docs/11 §5 DoD manual bring-up (verify-loop, admin surface on local Ktor boot) before archive.
