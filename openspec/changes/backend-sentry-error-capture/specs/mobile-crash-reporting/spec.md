## REMOVED Requirements

### Requirement: Backend error capture is out of scope (deferred)
**Reason**: The deferral is resolved — `backend-sentry-error-capture` (issue #316) adds Sentry-Java error capture to `:backend:ktor`, so this requirement's "No Sentry in the backend" scenario is now false by design.
**Migration**: Backend error-reporting behavior is specified by the new `backend-error-reporting` capability (`:infra:sentry-jvm` + `SentryBootstrap`). Mobile crash reporting is unchanged.
