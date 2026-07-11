# Tasks: orphan-image-cleanup

## 1. Infra — ImageStore.delete

- [x] 1.1 Add `suspend fun delete(imageId: String)` to `ImageStore`; `NoOpImageStore` throws `IllegalStateException` (same gate-on-`isConfigured()` contract as `upload`)
- [x] 1.2 Implement `CloudflareImageStore.delete` — `DELETE /accounts/{accountId}/images/v1/{imageId}`, bearer auth, 404 = success, other non-2xx → `CloudflareImageStoreException`
- [x] 1.3 Unit tests (MockEngine): 200 success, 404 treated as success, 500 throws

## 2. Backend — worker + repository + route

- [x] 2.1 `OrphanImageCleanupRepository` (`backend/ktor/.../image/`): `findOrphans()` (threshold scan, oldest-first, LIMIT 500) + per-row transactional delete helper (`deleteOrphanRow(conn, cfImageId)` conditional on `status = 'uploaded'`)
- [x] 2.2 `OrphanImageCleanupWorker`: `isConfigured()` fail-soft no-op; per-row transaction per design D2 (conditional DELETE → CF delete → commit; rollback on CF failure); result counts (`scanned`/`deleted`/`cfFailures`); single INFO log line `event=orphan_image_cleanup` with counts + `duration_ms`
- [x] 2.3 `OrphanImageCleanupRoutes`: `POST /cleanup-orphan-images` under the parent `route("/internal")`, `InternalEndpointAuth` on its own subtree, `200` counts body / sanitized `500` via `classifyHandlerError` (mirror `retentionCleanupRoutes`)
- [x] 2.4 Wire in `Application.kt`: repository + worker construction, route mount next to `retentionCleanupRoutes`

## 3. Tests — backend

- [x] 3.1 Worker DB tests (`!network`-tagged, per docs/13): aged orphan swept; fresh (<24h) row untouched; `attached` row untouched; CF failure → rollback + row survives + `cf_failures` count; CF 404-as-success covered at the infra layer (worker sees it as a normal success); concurrent-attach flip → zero-row DELETE skips CF call; idempotent all-zero re-run; unconfigured store → no-op all-zero
- [x] 3.2 Route tests: 401 without OIDC bearer; 200 counts body with verified token; 500 sanitized error body on worker throw; `InternalRoutingIsolationTest` still green (sibling webhook subtrees untouched)

## 4. Spec sync + docs

- [x] 4.1 Verify delta specs validate: `openspec validate orphan-image-cleanup --strict`
- [ ] 4.2 Operator task (preflight): create daily Cloud Scheduler jobs (staging + production) targeting `POST /internal/cleanup-orphan-images` with the existing `/internal/cleanup` OIDC service account

## 5. Gate + PR

- [x] 5.1 `./gradlew ktlintCheck detekt :backend:ktor:test :lint:detekt-rules:test` green locally
- [x] 5.2 PR body current (proposal summary, verification evidence, `Closes #340`)
