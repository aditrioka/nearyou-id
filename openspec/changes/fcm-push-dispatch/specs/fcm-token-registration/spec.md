## MODIFIED Requirements

### Requirement: Schema MUST support the deferred Phase 2 on-send-failure delete and Phase 3.5 stale-cleanup contracts

The `user_fcm_tokens` schema introduced by this change MUST be designed such that two GC paths can be efficiently implemented WITHOUT any further schema migration:

1. **On-send-failure delete (owned by [`fcm-push-dispatch`](../../specs/fcm-push-dispatch/spec.md))** — when an FCM Admin SDK send returns `MessagingErrorCode.UNREGISTERED` (HTTP 404), `INVALID_ARGUMENT` (HTTP 410), OR `SENDER_ID_MISMATCH` for a given `(user_id, platform, token)` triple, the `FcmDispatcher` SHALL execute `DELETE FROM user_fcm_tokens WHERE user_id = :u AND platform = :p AND token = :t`. The UNIQUE index on `(user_id, platform, token)` introduced by `fcm-token-registration` MUST make this DELETE a single index-lookup operation. This is the on-send-failure GC path per [`docs/05-Implementation.md:1399`](docs/05-Implementation.md). The contract was originally booked as "Phase 2 on-send-failure delete (deferred)" by the V14 fcm-token-registration change; the `fcm-push-dispatch` change now owns it.

2. **Phase 3.5 stale-cleanup worker (still deferred)** — on a weekly schedule via `/internal/cleanup` (OIDC-authed Cloud Scheduler call), the future worker SHALL execute `DELETE FROM user_fcm_tokens WHERE last_seen_at < NOW() - INTERVAL '30 days'`. The `user_fcm_tokens_last_seen_idx` index on `last_seen_at` introduced by `fcm-token-registration` MUST make this DELETE an index-range scan (NOT a full-table scan). This is the long-tail backstop per [`docs/05-Implementation.md:1400`](docs/05-Implementation.md). Phase 3.5 admin-panel territory; remains deferred.

This requirement constrains the schema design so both GC paths are efficient. The first contract's owner is now `fcm-push-dispatch`; the second's owner is the future Phase 3.5 stale-cleanup-worker change.

#### Scenario: Schema supports the on-send delete shape (now owned by fcm-push-dispatch)

- **WHEN** `fcm-push-dispatch`'s `FcmDispatcher` implements the on-send `DELETE FROM user_fcm_tokens WHERE user_id = :u AND platform = :p AND token = :t`
- **THEN** the DELETE uses the UNIQUE index on `(user_id, platform, token)` (single index lookup, no full-table scan)

#### Scenario: Schema supports the deferred stale-cleanup shape

- **WHEN** a future change implements the Phase 3.5 weekly `DELETE FROM user_fcm_tokens WHERE last_seen_at < NOW() - INTERVAL '30 days'`
- **THEN** the DELETE uses the `user_fcm_tokens_last_seen_idx` index on `last_seen_at` (range scan, no full-table scan)
