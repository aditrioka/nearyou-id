## MODIFIED Requirements

### Requirement: Schema MUST support the deferred Phase 2 on-send-failure delete and Phase 3.5 stale-cleanup contracts

The `user_fcm_tokens` schema introduced by this change MUST be designed such that two GC paths can be efficiently implemented WITHOUT any further schema migration:

1. **On-send-prune (owned by [`fcm-push-dispatch`](../../specs/fcm-push-dispatch/spec.md))** — when an FCM Admin SDK send returns `MessagingErrorCode.UNREGISTERED` OR `MessagingErrorCode.SENDER_ID_MISMATCH` for a given `(user_id, platform, token)` triple, the `FcmDispatcher` SHALL execute a race-guarded DELETE:

   ```sql
   DELETE FROM user_fcm_tokens
   WHERE user_id = :u
     AND platform = :p
     AND token = :t
     AND last_seen_at <= :dispatch_started_at
   ```

   `MessagingErrorCode.INVALID_ARGUMENT` is NOT a delete trigger because it is overloaded by the FCM Admin SDK between stale-token-format failures AND oversized-payload failures (see `fcm-push-dispatch` design D6); deleting on `INVALID_ARGUMENT` would wrongly prune healthy tokens whose payload happened to exceed the APNs 4 KB limit.

   The `last_seen_at <= :dispatch_started_at` predicate is the re-registration race guard (`fcm-push-dispatch` design D12): a row whose `last_seen_at` is later than the dispatcher's read-time has been re-registered by a fresh `POST /api/v1/user/fcm-token` upsert during the dispatch window, and MUST NOT be deleted.

   The UNIQUE index on `(user_id, platform, token)` introduced by `fcm-token-registration` MUST make this DELETE a single index-lookup operation; the additional `last_seen_at` filter is applied to the matched row in-memory after the index seek, no separate index needed. This is the on-send-failure GC path per [`docs/05-Implementation.md:1399`](docs/05-Implementation.md). The contract was originally booked as "Phase 2 on-send-failure delete (deferred)" by the V14 fcm-token-registration change; the `fcm-push-dispatch` change now owns it AND has narrowed the trigger codes (UNREGISTERED + SENDER_ID_MISMATCH only) AND added the race guard.

2. **Phase 3.5 stale-cleanup worker (still deferred)** — on a weekly schedule via `/internal/cleanup` (OIDC-authed Cloud Scheduler call), the future worker SHALL execute `DELETE FROM user_fcm_tokens WHERE last_seen_at < NOW() - INTERVAL '30 days'`. The `user_fcm_tokens_last_seen_idx` index on `last_seen_at` introduced by `fcm-token-registration` MUST make this DELETE an index-range scan (NOT a full-table scan). This is the long-tail backstop per [`docs/05-Implementation.md:1400`](docs/05-Implementation.md). Phase 3.5 admin-panel territory; remains deferred.

This requirement constrains the schema design so both GC paths are efficient. The first contract's owner is now `fcm-push-dispatch`; the second's owner is the future Phase 3.5 stale-cleanup-worker change.

#### Scenario: Schema supports the on-send-prune shape (now owned by fcm-push-dispatch, narrowed triggers + race-guard)

- **WHEN** `fcm-push-dispatch`'s `FcmDispatcher` implements the on-send `DELETE FROM user_fcm_tokens WHERE user_id = :u AND platform = :p AND token = :t AND last_seen_at <= :dispatch_started_at`
- **THEN** the DELETE uses the UNIQUE index on `(user_id, platform, token)` (single index lookup) AND the `last_seen_at` predicate is applied as a row-level filter AND the DELETE only fires for `UNREGISTERED` / `SENDER_ID_MISMATCH` (not `INVALID_ARGUMENT`)

#### Scenario: Schema supports the re-registration race semantic

- **WHEN** the FcmDispatcher attempts to prune a token whose `last_seen_at` was bumped between the dispatcher's read-time and the FCM error response (a re-registration race)
- **THEN** the DELETE returns 0 rows affected (predicate `last_seen_at <= :dispatch_started_at` does not match the now-fresh row) AND the row persists, preserving the freshly-re-registered token

#### Scenario: Schema supports the deferred stale-cleanup shape

- **WHEN** a future change implements the Phase 3.5 weekly `DELETE FROM user_fcm_tokens WHERE last_seen_at < NOW() - INTERVAL '30 days'`
- **THEN** the DELETE uses the `user_fcm_tokens_last_seen_idx` index on `last_seen_at` (range scan, no full-table scan)
