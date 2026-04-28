## MODIFIED Requirements

### Requirement: NotificationDispatcher seam for future FCM

An interface `NotificationDispatcher` SHALL be defined in `:core:data` with a single method `fun dispatch(notification: NotificationDto)`. The capability SHALL ship two production implementations:

- `InAppOnlyDispatcher` — a no-op (logs at INFO `event="notification_in_app_only"`, does not push anywhere). Originally introduced in V10 as the only implementation; preserved as the audit-log baseline AND the test-profile default.
- `FcmDispatcher` — implemented in `:infra:fcm` per the [`fcm-push-dispatch` capability](../../specs/fcm-push-dispatch/spec.md). Reads active rows from `user_fcm_tokens` for the recipient AND sends platform-specific FCM pushes (Android data-only / iOS alert + mutable-content). Owns the on-send 404/410 → row-delete contract per [`fcm-token-registration`](../../specs/fcm-token-registration/spec.md).

The Koin DI module for `:backend:ktor` production startup SHALL bind `NotificationDispatcher` to a composite implementation (`FcmAndInAppDispatcher`) that calls both `FcmDispatcher.dispatch(notification)` AND `InAppOnlyDispatcher.dispatch(notification)` in sequence — preserving the in-app log line as the audit trail AND adding the FCM push alongside it. The integration-test Koin module SHALL bind `InAppOnlyDispatcher` only by default; tests that exercise FCM dispatch MUST install a test-only override binding.

`NotificationService.emit()` SHALL call `dispatch()` after the DB commit succeeds (post-commit invocation contract). The contract is preserved verbatim from V10: `NotificationService` source is unchanged by the addition of `FcmDispatcher`. Future dispatcher implementations (e.g., per-conversation push batching when chat lands) MAY add new `NotificationDispatcher` implementations or new composites without modifying `NotificationService` or any emitter.

#### Scenario: Interface lives in :core:data

- **WHEN** inspecting the `:core:data` module sources
- **THEN** `NotificationDispatcher` interface is present AND has no vendor imports

#### Scenario: In-app-only implementation is a no-op

- **WHEN** `InAppOnlyDispatcher.dispatch(...)` is called
- **THEN** a log line at INFO is emitted AND no push / network call is made

#### Scenario: FcmDispatcher implementation lives in :infra:fcm

- **WHEN** inspecting the `:infra:fcm` module sources
- **THEN** a class implementing `NotificationDispatcher` is present AND it is the only `NotificationDispatcher` implementation in `:infra:fcm`

#### Scenario: Production DI binds the composite

- **WHEN** the backend starts in the production profile AND `Koin.get<NotificationDispatcher>()` is resolved
- **THEN** the resolved instance is the composite `FcmAndInAppDispatcher` AND `dispatch(...)` invokes both `FcmDispatcher.dispatch(...)` AND `InAppOnlyDispatcher.dispatch(...)` exactly once each

#### Scenario: Test profile DI binds InAppOnlyDispatcher only

- **WHEN** the integration-test Koin module is loaded AND `Koin.get<NotificationDispatcher>()` is resolved
- **THEN** the resolved instance is `InAppOnlyDispatcher` AND no FCM dispatch occurs during the default test run

#### Scenario: Dispatcher called after commit

- **WHEN** `NotificationService.emit(...)` completes its DB transaction successfully
- **THEN** `NotificationDispatcher.dispatch(...)` is invoked exactly once with the emitted notification DTO

#### Scenario: Dispatcher NOT called on emit suppression

- **WHEN** `NotificationEmitter` short-circuits with `suppressed_reason = "self_action" | "blocked"`
- **THEN** `NotificationDispatcher.dispatch(...)` is NOT invoked (no row to dispatch)

#### Scenario: Dispatcher NOT called on primary-write rollback

- **WHEN** the primary write transaction rolls back (e.g. notification INSERT fails)
- **THEN** `NotificationDispatcher.dispatch(...)` is NOT invoked

#### Scenario: NotificationService source unchanged by FcmDispatcher addition

- **WHEN** comparing the source of `NotificationService` before and after the FCM-push-dispatch change lands
- **THEN** the diff for `NotificationService` is empty (the integration is purely additive in `:infra:fcm` and DI wiring)
