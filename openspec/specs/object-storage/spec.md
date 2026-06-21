# object-storage Specification

## Purpose
The object-storage capability provides a vendor-neutral `ObjectStore` contract (`:infra:r2`) — store an object, issue a time-limited signed GET URL, and delete — backed by Cloudflare R2 (S3-compatible) via the AWS SDK for Kotlin. The vendor SDK is fenced inside `:infra:r2` (no `aws.sdk.*` / `aws.smithy.*` import outside the module, per the no-vendor-SDK-outside-`:infra` invariant); credentials are resolved via the secret helper; and a fail-soft `NoOpObjectStore` binds when R2 credentials are unset so application boot never fails on un-provisioned storage. It is a reusable substrate — first consumed by `account-data-export` for export archives; future consumers include image upload, the CSAM archive, and backups.
## Requirements
### Requirement: Vendor-neutral object-storage contract

A new `:infra:r2` module SHALL provide a vendor-neutral `ObjectStore` interface — `put(key, bytes, contentType)`, `presignedGetUrl(key, ttl)`, `delete(key)` — backed by Cloudflare R2 via the AWS SDK for Kotlin S3 client pointed at the R2 S3 endpoint. No R2/AWS SDK type (`aws.sdk.kotlin.*`, `aws.smithy.*`) may be imported outside `:infra:r2` (the no-vendor-SDK-outside-`:infra:*` invariant); `:backend:ktor` depends only on the `ObjectStore` interface. R2 credentials (account id, access key id, secret access key, bucket) MUST be resolved via the `secretKey(env, name)` helper, never direct env reads.

#### Scenario: Stored object is retrievable via a signed URL within its TTL
- **WHEN** an object is `put` and `presignedGetUrl(key, ttl)` is called and the returned URL is fetched before `ttl` elapses
- **THEN** the fetch returns the stored bytes

#### Scenario: Signed URL stops working after its TTL
- **WHEN** the same signed URL is fetched after `ttl` has elapsed
- **THEN** the object store rejects it (the URL is expired) and the bytes are not served

#### Scenario: No vendor SDK type leaks outside the module
- **WHEN** the codebase is scanned for `aws.sdk.kotlin` / `aws.smithy` imports
- **THEN** they appear only under `:infra:r2`, and `:backend:ktor` references only the `ObjectStore` interface

#### Scenario: Credentials resolved via the secret helper
- **WHEN** the `:infra:r2` configuration is constructed
- **THEN** every R2 credential is read through `secretKey(env, name)` (no direct `System.getenv` of a secret name)

### Requirement: Object storage fails soft when unconfigured

When the R2 credentials are unset (dev/test/un-provisioned staging), the module SHALL bind a no-op `ObjectStore` so application boot does not fail and consuming workers can degrade gracefully (the `NoOpImageModerator` precedent). The no-op implementation MUST NOT throw an unhandled exception that crashes a consumer; it surfaces a defined "unconfigured" outcome the consumer maps to a soft failure.

#### Scenario: App boots with R2 unconfigured
- **WHEN** the application starts with no R2 credentials present
- **THEN** boot succeeds and a no-op `ObjectStore` is bound

#### Scenario: No-op store degrades gracefully
- **WHEN** a consumer calls `put` / `presignedGetUrl` on the no-op store
- **THEN** the call returns a defined "unconfigured" result (no successful URL) without throwing an unhandled crash

