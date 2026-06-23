# mobile-amplitude-analytics Specification

## Purpose
This capability governs the mobile consent-gated product-analytics tracker — the missing consumer of the `mobile-analytics-consent` analytics toggle (its crash sibling is consumed by `mobile-crash-reporting`). It owns the vendor-SDK-free `:infra:amplitude` seam: an `AnalyticsTracker` interface, a `NoOpAnalyticsTracker` default, and an `AmplitudeAnalyticsTracker` HTTP V2 API wrapper (per `docs/04` § Amplitude) over a dedicated **no-Auth** Ktor client so the app's RS256 JWT never reaches the third-party host. An app-layer `ConsentGatedAnalyticsTracker` decorator reads the durable `ConsentSnapshotStore` per emission and **silently suppresses** all events/identify/flush when analytics consent is off (the UU-PDP "opt-out silent" requirement; `docs/06` § Analytics & Tracking Consent). The shipped foundational event slice is the two post-authentication funnel events `signup_completed` + `post_created` (each carrying only `user_id`, no coordinates or content); `identify` + the user-property set, the `post_liked`/`post_viewed` engagement events, the full premium/chat/moderation taxonomy, backend-fired security events, and the pre-auth `app_opened` + `device_id` seam are explicitly deferred to follow-up issues. The Amplitude ingestion key is a client-embeddable write key delivered per build flavor (blank → `NoOp`).
## Requirements
### Requirement: Consent-gated analytics emission

The mobile app SHALL gate every Amplitude analytics emission (events, `identify`, and `flush`) on the analytics-consent value read from the **durable** `ConsentSnapshotStore` (`read()?.analytics`), re-evaluated on each emission, and SHALL silently suppress — emit no network request and surface no error or UI — when analytics consent is `FALSE` or the consent snapshot is absent. (Satisfies the `docs/08` Pre-Launch requirement "Analytics consent suppression tested — Amplitude opt-out silent.")

#### Scenario: Analytics consent off suppresses emission
- **WHEN** analytics consent is `FALSE` and any tracked event or identify is invoked
- **THEN** no HTTP request is issued to Amplitude AND no error is surfaced to the caller

#### Scenario: Absent consent snapshot is treated as off
- **WHEN** no consent snapshot has been persisted yet and a tracked event is invoked
- **THEN** emission is suppressed (the absent snapshot defaults to `analytics = false`)

#### Scenario: Analytics consent on permits emission
- **WHEN** analytics consent is `TRUE` and a tracked event is invoked
- **THEN** the event is emitted to Amplitude via the configured transport

#### Scenario: Consent is re-evaluated on every emission
- **WHEN** analytics consent is `TRUE`, an event is emitted, the user then toggles analytics `OFF` in Settings, and a later event is invoked in the same session
- **THEN** the later event is suppressed (no re-initialization is required for the toggle to take effect)

### Requirement: Vendor-SDK-free Amplitude HTTP V2 transport

The `:infra:amplitude` module SHALL provide an `AnalyticsTracker` whose implementation posts events to the configured Amplitude HTTP V2 ingestion endpoint using the project's Ktor client + `kotlinx.serialization` stack, with NO Amplitude vendor SDK dependency. The transport SHALL be fail-soft: transport, timeout, non-2xx, or serialization failures never propagate to or block the caller. The transport SHALL use a dedicated HTTP client that does NOT attach the app's authentication (no `Authorization` bearer), so no app credential is sent to the third-party host.

#### Scenario: Event request carries the HTTP V2 body shape
- **WHEN** an event is emitted with consent on
- **THEN** the request POSTs to the configured ingestion endpoint with a body containing `api_key` and an `events` array whose element carries `event_type`, `user_id`, `event_properties`, `user_properties`, and `time`

#### Scenario: Ingestion endpoint is configurable for data residency
- **WHEN** the tracker is constructed with the default configuration
- **THEN** events POST to the US endpoint (`https://api2.amplitude.com/2/httpapi`) AND the endpoint is overridable via configuration to the EU host (`https://api.eu.amplitude.com/2/httpapi`) with no code change

#### Scenario: Transport failure is fail-soft
- **WHEN** the Amplitude endpoint returns a network error or non-2xx response
- **THEN** the emitting call returns normally with no exception propagated and no user-visible effect

#### Scenario: No app credential is leaked to Amplitude
- **WHEN** an event request is built
- **THEN** the request carries no `Authorization` header (the app's RS256 JWT is never sent to Amplitude)

### Requirement: NoOp tracker when Amplitude is unconfigured

When the Amplitude ingestion API key is blank or absent, the app SHALL bind a no-op `AnalyticsTracker` that performs no network activity, so the app builds and runs (in dev and on un-provisioned builds) without Amplitude configuration. (Mirrors the `NoOpCrashReporter` blank-DSN precedent.)

#### Scenario: Blank API key binds the no-op tracker
- **WHEN** the Amplitude API key is blank and a tracked event is invoked
- **THEN** the no-op tracker is bound AND no HTTP request is attempted

### Requirement: Identify and the user-property set are deferred and tracked

This change SHALL NOT implement `identify` or the user-property set (`subscription_status`, `platform`, `install_date_bucket`, `city_name_at_last_post`). Three of the four properties need data the client does not currently hold — `subscription_status` (a session-level entitlement state), `install_date_bucket` (a persisted first-launch date), and `city_name_at_last_post` (post-city tracking) — so a faithful `identify` is its own data-sourcing scope. The foundational events carry `user_id`, so Amplitude still associates them to a user without `identify`. The deferral SHALL be recorded as a `follow-up` GitHub issue.

#### Scenario: identify is not wired, and the deferral is tracked
- **WHEN** this change is implemented
- **THEN** no `identify` or user-property emission is wired AND a `follow-up` GitHub issue ([#397](https://github.com/aditrioka/nearyou-id/issues/397)) tracks the deferred `identify` + user-property sourcing

### Requirement: Foundational post-authentication event slice

The app SHALL emit the events `signup_completed` and `post_created` (each consent-gated) at their existing success call sites in `:mobile:app`. Each event SHALL carry the authenticated `user_id` and SHALL include only privacy-safe `event_properties` — never raw coordinates and never post or message content. The engagement events `post_liked` and `post_viewed` are deferred with the broader taxonomy (see the deferral requirement below).

#### Scenario: Successful post creation emits post_created
- **WHEN** a post is created successfully and analytics consent is on
- **THEN** a `post_created` event is emitted carrying the authenticated `user_id`

#### Scenario: Successful signup emits signup_completed
- **WHEN** signup completes successfully and analytics consent is on
- **THEN** a `signup_completed` event is emitted carrying the authenticated `user_id`

#### Scenario: Event properties exclude sensitive data
- **WHEN** any foundational event is emitted
- **THEN** its `event_properties` contain no raw coordinates and no post/message content

### Requirement: Pre-authentication app_opened event and device_id seam are deferred and tracked

This change SHALL NOT emit the pre-authentication `app_opened` event and SHALL NOT introduce a `device_id` identity seam — the foundational slice is post-authentication only. The deferral SHALL be recorded as a `follow-up` GitHub issue so the install→signup onboarding-funnel completion (which requires `app_opened` + a persisted `device_id` for anonymous→identified stitching) has a tracked home.

#### Scenario: No app_opened and no device_id are introduced, and the deferral is tracked
- **WHEN** the app starts before the user authenticates
- **THEN** no `app_opened` event is emitted AND no `device_id` is generated or persisted AND a `follow-up` GitHub issue ([#395](https://github.com/aditrioka/nearyou-id/issues/395)) tracks the deferred pre-auth onboarding-funnel work

### Requirement: Full taxonomy and backend-fired events are deferred and tracked

This change SHALL implement only the `signup_completed` + `post_created` foundational events. The remaining engagement events (`post_liked`, `post_viewed`), the premium / chat / moderation event taxonomy, and backend-fired security events (`csam_detected`, `refresh_token_reused`, `attestation_failed`, webhook-signature-fail — which would require `:infra:amplitude` to gain a JVM/backend target), SHALL NOT be implemented in this change and SHALL be recorded as `follow-up` GitHub issue(s).

#### Scenario: Only the two foundational events exist, and the remainder is tracked
- **WHEN** this change is implemented
- **THEN** only `signup_completed` and `post_created` are wired AND no `post_liked` / `post_viewed` / `identify` / premium / chat / moderation / backend-fired events are wired AND `follow-up` GitHub issues ([#396](https://github.com/aditrioka/nearyou-id/issues/396), [#397](https://github.com/aditrioka/nearyou-id/issues/397)) track the deferred work

