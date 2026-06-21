## ADDED Requirements

### Requirement: Vendor-neutral transactional-email contract

A new `:infra:resend` module SHALL provide a vendor-neutral `EmailSender` interface — `send(to, template, idempotencyKey)` — backed by the Resend REST API (`POST /emails`) via a **raw Ktor client** (no official Resend Kotlin SDK exists; this mirrors the `:infra:cloudflare-images` "no JVM SDK → raw Ktor client" precedent, so it adds **no new library pin**). Email templates (HTML + text) MUST be versioned in-repo under `/backend/email-templates/`. The Resend API key MUST be resolved via `secretKey(env, name)`. No Resend-specific transport type may be imported outside `:infra:resend`; `:backend:ktor` depends only on the `EmailSender` interface.

#### Scenario: Send issues a Resend POST /emails with the rendered template
- **WHEN** `send(to, template, idempotencyKey)` is called against a configured sender
- **THEN** a `POST /emails` request is made to Resend carrying the recipient, subject, and rendered HTML + text from the template, and the `Idempotency-Key` header set to `idempotencyKey`

#### Scenario: Transient 5xx is retried with backoff
- **WHEN** Resend returns a `5xx` on the first attempt(s)
- **THEN** the sender retries up to 3 attempts total with exponential backoff before surfacing a failure result (it does not crash the caller)

#### Scenario: API key resolved via the secret helper
- **WHEN** the `:infra:resend` sender is constructed
- **THEN** the Resend API key is read through `secretKey(env, name)` (no direct secret-name env read)

### Requirement: Idempotent send prevents duplicate delivery

A retried send carrying the same `idempotencyKey` SHALL NOT produce a second delivery. The idempotency key for an event is `SHA256(user_id + event_type + timestamp_minute)` (docs/04 §Implementation), passed both as the Resend `Idempotency-Key` header and usable for caller-side dedup.

#### Scenario: Same idempotency key sends once
- **WHEN** two `send` calls are made with the same `idempotencyKey` (e.g. a retry of the same export-ready email)
- **THEN** the recipient receives exactly one email

### Requirement: Transactional email fails soft when unconfigured + leaks no PII

When the Resend API key is unset, the module SHALL bind a no-op `EmailSender` so boot does not fail and consumers degrade gracefully. The module MUST NOT log the recipient address or message body at any level (no-PII-in-logs); only non-PII metadata (event type, idempotency key, status) may be logged.

#### Scenario: App boots with Resend unconfigured
- **WHEN** the application starts with no Resend API key present
- **THEN** boot succeeds and a no-op `EmailSender` is bound; a `send` call no-ops without throwing

#### Scenario: Recipient and body are never logged
- **WHEN** a send succeeds or fails and the logs are inspected
- **THEN** no log line contains the recipient email address or the rendered message body
