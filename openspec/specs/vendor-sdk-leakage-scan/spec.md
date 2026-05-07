# vendor-sdk-leakage-scan Specification

## Purpose

Enforces the "No vendor SDK import outside `:infra:*`" invariant from `CLAUDE.md` § Critical invariants by scanning Kotlin source files in non-`:infra:*` modules (`:core:domain`, `:core:data`, `:backend:ktor`) for forbidden import prefixes (`io.supabase.`, `io.github.jan-tennert.supabase.`, `io.lettuce.`, `com.google.firebase.`). The scan is implemented as a Kotest spec (`VendorSdkLeakageScanTest`) under `:lint:detekt-rules` rather than a Detekt PSI rule because the check is a simple line-prefix match and runs alongside the rest of the lint test suite. Vendor adapters MUST live in `:infra:<vendor>` modules; non-infra code SHALL depend only on domain interfaces.

## Requirements

### Requirement: Vendor-SDK leakage scan lives in :lint:detekt-rules

A Kotest `StringSpec` test SHALL be added to `:lint:detekt-rules` at `lint/detekt-rules/src/test/kotlin/id/nearyou/lint/detekt/VendorSdkLeakageScanTest.kt`. The test runs as part of `./gradlew :lint:detekt-rules:test` (which `CLAUDE.md` § Pre-push verification mandates running locally before push, alongside `ktlintCheck`, `detekt`, and `:backend:ktor:test`).

#### Scenario: Test discovered by Gradle test task

- **WHEN** running `./gradlew :lint:detekt-rules:test`
- **THEN** the Kotest runner picks up `VendorSdkLeakageScanTest` and reports its result alongside the other rule tests in the module

### Requirement: Scan covers non-infra source roots only

The test SHALL walk Kotlin source files (`*.kt`) under exactly these source roots:

1. `core/domain/src/`
2. `core/data/src/`
3. `backend/ktor/src/`

`:infra:*` modules SHALL NOT be scanned — they are the canonical homes for vendor-SDK adapters and are explicitly exempt. `:shared:*` and `:mobile:app` modules are also out of scope for this iteration; if a future change adds vendor leakage risk to mobile or shared code, that change extends the source-root list with explicit reasoning in `design.md`.

#### Scenario: :infra:supabase imports allowed

- **WHEN** `infra/supabase/src/main/kotlin/.../SupabaseBroadcastChatClient.kt` contains `import io.github.jan-tennert.supabase.realtime.Realtime`
- **THEN** the scan does NOT flag this file (the source root `infra/supabase/src/` is not in the scan list)

#### Scenario: :backend:ktor leakage flagged

- **WHEN** any file under `backend/ktor/src/main/kotlin/` contains `import io.lettuce.core.RedisClient`
- **THEN** the scan reports the violation as `<relative-path>:<line>: import io.lettuce.core.RedisClient` and the test fails

#### Scenario: :core:domain leakage flagged

- **WHEN** any file under `core/domain/src/` contains `import com.google.firebase.messaging.Message`
- **THEN** the scan reports the violation and the test fails

### Requirement: Forbidden import prefix list

The scan SHALL match these exact import prefixes (string `startsWith` after `import` keyword):

1. `io.supabase.` — Supabase server-side SDK; canonical home `:infra:supabase`
2. `io.github.jan-tennert.supabase.` — community Kotlin Supabase client; canonical home `:infra:supabase`
3. `io.lettuce.` — Lettuce Redis client; canonical home `:infra:redis`
4. `com.google.firebase.` — Firebase Admin SDK (FCM dispatch); canonical home `:infra:fcm`

The list MAY be extended when a new vendor SDK is introduced — extension SHALL ship in the same OpenSpec change that introduces the `:infra:<vendor>` module that owns it. The list SHALL NOT include OTel API types (`io.opentelemetry.api.trace.Span`, `io.opentelemetry.api.trace.Tracer`) — those are intentional consumption surfaces in `:backend:ktor` (per `observability-otel-foundation` spec) and are explicitly exempt from this scan even though OTel SDK + exporter packages live in `:infra:otel`. If a future change forbids more granular OTel imports (e.g., `io.opentelemetry.sdk.*`, `io.opentelemetry.exporter.otlp.*`) outside `:infra:otel`, that change amends this requirement.

#### Scenario: Trimmed prefix match required

- **WHEN** a file contains `   import io.lettuce.core.RedisClient` (leading whitespace)
- **THEN** the scan trims the leading whitespace and recognises this as a forbidden prefix

#### Scenario: Substring matches inside string literals are NOT flagged

- **WHEN** a file contains `val msg = "use io.lettuce.* via :infra:redis"` (non-import line referencing the prefix)
- **THEN** the scan does NOT flag this line (it skips any line that does not begin with `import` after trimming)

#### Scenario: OTel API import in :backend:ktor allowed

- **WHEN** `backend/ktor/src/main/kotlin/.../WithSpan.kt` contains `import io.opentelemetry.api.trace.Span`
- **THEN** the scan does NOT flag this file (OTel API is exempt; only the SDK + exporter packages would be added in a future amendment)

### Requirement: Violation report format

When violations are found the test SHALL:

1. Collect every offending `(file, line, content)` triple across all scanned source roots into a single list.
2. Fail with an `error(...)` message that begins with the human-readable explanation `"Vendor SDK imports detected outside :infra:* modules"` plus the canonical-fix instruction (`Move the import to the appropriate ":infra:<vendor>" adapter and depend on a domain interface from the violating module`), followed by a blank line, then the violations joined by newline.
3. Conclude with `violations shouldBe emptyList()` so Kotest reports the assertion site cleanly.

This format mirrors the violation-report shape used by other source-scan rules in this module (`BlockExclusionJoinRule`'s test fixtures, etc.) and minimises noise in CI logs.

#### Scenario: Single violation reported with file path + line + content

- **WHEN** exactly one violation is found in `backend/ktor/src/main/kotlin/id/nearyou/app/example/Foo.kt` at line 7 with content `import io.lettuce.core.RedisClient`
- **THEN** the failure message contains the substring `backend/ktor/src/main/kotlin/id/nearyou/app/example/Foo.kt:7: import io.lettuce.core.RedisClient`

#### Scenario: Multiple violations all reported

- **WHEN** three violations exist across two files
- **THEN** all three are listed in the failure message, one per line, in source-root traversal order

### Requirement: Composes with other lint capabilities

`vendor-sdk-leakage-scan` SHALL run alongside the Detekt PSI rules (`RawFromPostsRule`, `BlockExclusionJoinRule`, `CoordinateJitterRule`, `RateLimitTtlRule`, `RedisHashTagRule`, `RawXForwardedForRule`) without overlap or interference. The PSI rules inspect Kotlin string literals and call expressions; this scan inspects only `import` lines. A file MAY trip both this scan and a Detekt rule independently — no de-duplication is required, and neither side suppresses the other.

#### Scenario: Lint module test task runs both scan + Detekt rule tests

- **WHEN** running `./gradlew :lint:detekt-rules:test`
- **THEN** both `VendorSdkLeakageScanTest` (this capability) and the Detekt rule tests (`BlockExclusionJoinLintTest`, `RawFromPostsRuleTest`, etc.) execute and report independently

### Requirement: Scope boundaries (deferred work)

This capability SHALL NOT include:

1. A Detekt PSI rule for vendor-SDK leakage — the current Kotest scan is sufficient because the check is a line-prefix match, and adding a PSI rule would duplicate coverage with no precision gain.
2. SQL file scanning — the scan operates only on `*.kt` files; SQL migrations cannot import vendor SDKs anyway.
3. Mobile / `:shared:*` source roots — covered by `mobile/app` once mobile work begins; deferred until then.
4. OTel SDK + exporter package restrictions — the current allowance of `io.opentelemetry.api.*` in `:backend:ktor` is intentional. A future change MAY tighten this to forbid `io.opentelemetry.sdk.*` and `io.opentelemetry.exporter.otlp.*` outside `:infra:otel`; that change amends Requirement § "Forbidden import prefix list".

#### Scenario: SQL files are not scanned

- **WHEN** running the test against the repository
- **THEN** no `*.sql` file is opened or read by the scan
