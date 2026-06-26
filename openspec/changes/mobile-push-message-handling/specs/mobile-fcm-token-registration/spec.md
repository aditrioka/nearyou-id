## RENAMED Requirements

- FROM: `### Requirement: Push message display and handling are deferred`
- TO: `### Requirement: Push message display and handling are implemented by mobile-push-message-handling`

## MODIFIED Requirements

### Requirement: Push message display and handling are implemented by mobile-push-message-handling

This capability stops at token registration; incoming-push **display/handling** SHALL be provided by the separate `mobile-push-message-handling` capability (Android `onMessageReceived` local-notification rendering with the content-privacy preference check, and the iOS Notification Service Extension body rewrite — `docs/04-Architecture.md` §461–509), NOT by this token-registration capability. Within THIS capability's own change, no incoming-message handler was added beyond the token-refresh bridge; the display/handling lives entirely in `mobile-push-message-handling` (follow-up issue [#256](https://github.com/aditrioka/nearyou-id/issues/256)), which MUST extend the shipped `NearYouFirebaseMessagingService` rather than duplicating it.

#### Scenario: Display/handling lives in mobile-push-message-handling, not the token-registration change

- **WHEN** inspecting the `mobile-fcm-token-registration` change's diff
- **THEN** that change added no incoming-push display/handling code (no local-notification builder, no NSE) beyond the token-refresh bridge — the local-notification rendering and the iOS NSE ship in the `mobile-push-message-handling` capability (issue [#256](https://github.com/aditrioka/nearyou-id/issues/256)), which owns the `onMessageReceived` override on the shared service
