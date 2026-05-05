## ADDED Requirements

### Requirement: WARN-log scenarios SHALL pair with an OTel span event

For each WARN log path defined by the existing requirements (INVALID_ARGUMENT, retryable / 5xx errors, network timeout / unknown errors, per-token failure inside a multi-token dispatch, dispatch-after-shutdown), `FcmDispatcher` SHALL ALSO record an OTel span event on the surrounding `"fcm.dispatch"` span with attributes that match the WARN log line's fields exactly:
- `event = <same string as the WARN log's event field>` (e.g., `"fcm_dispatch_failed"`, `"fcm_dispatch_after_shutdown"`).
- `error_code = <same value as the WARN log's error_code field>` (e.g., `"INVALID_ARGUMENT"`, `"INTERNAL"`, `"UNAVAILABLE"`, `"QUOTA_EXCEEDED"`, `"unknown"`). The `error_code` attribute SHALL NOT appear when the WARN log line itself does not include an `error_code` field.

The surrounding `"fcm.dispatch"` span SHALL be created via `:infra:otel`'s `withSpan(...)` helper around the FCM Admin SDK send call. The span's attributes SHALL include `messaging.system = "fcm"`, `messaging.destination.kind = "topic"` (semconv-aligned), AND the user.id attribute set via `UserIdHasher.hash(userId)` (per `observability-otel-foundation` § "Mandatory span attributes" — recipient user is the relevant principal in the dispatch path). The span SHALL NEVER carry the raw FCM token, the raw user UUID, the raw notification body content, or any other forbidden attribute per the `observability-otel-foundation` § "Forbidden span attributes" requirement.

Span event recording SHALL be best-effort. If span recording itself throws (e.g., the SDK is in a degraded state), the exception SHALL be silently swallowed AND SHALL NOT block the dispatch, propagate to the caller, or change the WARN log emission. This is consistent with the existing FATAL→ERROR+metric downgrade in [`fcm-push-dispatch/spec.md`](../../../../specs/fcm-push-dispatch/spec.md) § "Composite dispatcher unexpected exception fan-out" — observability is a side-effect surface that MUST NOT be in the critical-path of dispatch.

#### Scenario: INVALID_ARGUMENT WARN log pairs with span event
- **GIVEN** FCM responds `INVALID_ARGUMENT` for a token AND the OTel SpanRecorder test-double captures all emitted spans
- **WHEN** `dispatch(notification)` completes
- **THEN** a WARN log line is captured with `event="fcm_dispatch_failed"` AND `error_code="INVALID_ARGUMENT"` (existing behavior unchanged) AND the surrounding `"fcm.dispatch"` span captures an event with attributes `event="fcm_dispatch_failed"` AND `error_code="INVALID_ARGUMENT"`

#### Scenario: 5xx-class error WARN log pairs with span event
- **GIVEN** FCM responds `INTERNAL` for a token
- **WHEN** `dispatch(notification)` completes
- **THEN** a WARN log line is captured with `error_code="INTERNAL"` AND the surrounding `"fcm.dispatch"` span captures an event with `event="fcm_dispatch_failed"` AND `error_code="INTERNAL"`

#### Scenario: Network timeout WARN log pairs with span event
- **GIVEN** the FCM Admin SDK call throws a non-`FirebaseMessagingException` (e.g., a network timeout)
- **WHEN** `dispatch(notification)` completes
- **THEN** a WARN log line is captured with `event="fcm_dispatch_failed"` AND `error_code="unknown"` AND the surrounding `"fcm.dispatch"` span captures an event with `event="fcm_dispatch_failed"` AND `error_code="unknown"` AND `Span.recordException(...)` captured the thrown timeout

#### Scenario: Per-token partial-failure pairs with one span event per failed token
- **GIVEN** `dispatch(notification)` is invoked for a recipient with 3 tokens AND the first FCM send throws `RuntimeException` AND the second and third succeed
- **WHEN** `dispatch(notification)` completes
- **THEN** the WARN log line for the failing token is captured (existing behavior unchanged) AND a span event with `event="fcm_dispatch_failed"` is recorded AND no span event is recorded for the two succeeding tokens

#### Scenario: dispatch-after-shutdown pairs with span event
- **GIVEN** the `fcmDispatcherScope` has been cancelled via the JVM shutdown hook AND a new `dispatch(notification)` call arrives
- **WHEN** the dispatch attempt observes the closed scope
- **THEN** a WARN log line is captured with `event="fcm_dispatch_after_shutdown"` AND a `"fcm.dispatch"` span captures an event with `event="fcm_dispatch_after_shutdown"`

#### Scenario: Successful dispatch records span with OK status, no failure event
- **GIVEN** `dispatch(notification)` is invoked for a recipient with 1 token AND the FCM send succeeds
- **WHEN** `dispatch(notification)` completes
- **THEN** the surrounding `"fcm.dispatch"` span has status OK AND no event with `event="fcm_dispatch_failed"` is recorded

#### Scenario: Span recording failure does not block dispatch
- **GIVEN** the OTel SDK is in a degraded state where `Span.addEvent(...)` throws on every call AND FCM responds `INVALID_ARGUMENT` for a token
- **WHEN** `dispatch(notification)` completes
- **THEN** the WARN log line is still emitted (existing behavior preserved) AND the dispatcher returns normally (no exception escapes to the caller) AND the FCM send was still attempted (observability failure is non-blocking)

#### Scenario: Span carries no raw FCM token
- **GIVEN** `dispatch(notification)` is invoked for a recipient whose token is the well-known sentinel `"sentinel-token-string-DO-NOT-LEAK"` AND the OTel SpanRecorder captures all spans
- **WHEN** the captured `"fcm.dispatch"` span's attributes and events are scanned for the literal sentinel
- **THEN** the literal does NOT appear in any attribute value or event attribute value

#### Scenario: Span carries hashed user.id, not raw UUID
- **GIVEN** `dispatch(notification)` is invoked for a recipient whose user UUID is `U`
- **WHEN** the surrounding `"fcm.dispatch"` span's `user.id` attribute is inspected
- **THEN** the value equals `UserIdHasher.hash(U)` (16-hex truncated form) AND no attribute carries the raw UUID string of `U`
