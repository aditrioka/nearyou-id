# client-ip-extraction Specification

## Purpose
TBD - created by archiving change health-check-endpoints. Update Purpose after archive.
## Requirements
### Requirement: `clientIp` Ktor request-context value

A Ktor request-context attribute SHALL be populated at the top of the request pipeline with the resolved client IP address. The value MUST be reachable from any downstream handler via a typed accessor (e.g., `call.clientIp`) and MUST follow the canonical precedence ladder defined in [`docs/05-Implementation.md` § Cloudflare-Fronted IP Extraction](docs/05-Implementation.md):

1. `CF-Connecting-IP` header if present (Cloudflare origin-pull path — production + staging trustworthy path).
2. First entry in `X-Forwarded-For` if no `CF-Connecting-IP` (local dev fallback only).
3. `call.request.origin.remoteHost` as a last-resort fallback (raw socket address — local dev when no proxy header is set at all).

The resolved IP MUST be a string (NOT a parsed `InetAddress`) and MUST NOT be transformed (no normalization, no IPv6→IPv4 mapping). Trim leading/trailing whitespace from the header value. If the `X-Forwarded-For` header has multiple entries, take the first comma-separated entry only (the "client" hop closest to the actual user). If extraction fails entirely (no header AND no remoteHost), the value MUST be the literal string `"unknown"` so call-sites that key on it produce a deterministic bucket rather than crashing.

The intercept MUST run **before** authentication, rate-limiting, and any business handler.

#### Scenario: CF-Connecting-IP header takes precedence
- **WHEN** a request arrives with `CF-Connecting-IP: 1.2.3.4` AND `X-Forwarded-For: 5.6.7.8, 9.10.11.12`
- **THEN** `call.clientIp == "1.2.3.4"` (Cloudflare path is canonical when present)

#### Scenario: Falls back to first XFF entry when CF header absent
- **WHEN** a request arrives without `CF-Connecting-IP` AND with `X-Forwarded-For: 5.6.7.8, 9.10.11.12`
- **THEN** `call.clientIp == "5.6.7.8"` (first entry, NOT last)

#### Scenario: Falls back to remoteHost when both proxy headers absent
- **WHEN** a request arrives with neither `CF-Connecting-IP` nor `X-Forwarded-For` AND `call.request.origin.remoteHost == "127.0.0.1"`
- **THEN** `call.clientIp == "127.0.0.1"`

#### Scenario: Returns "unknown" when extraction fails entirely
- **WHEN** all three sources are empty or null
- **THEN** `call.clientIp == "unknown"` (no exception, deterministic bucket key)

#### Scenario: Trims whitespace
- **WHEN** the header value is `CF-Connecting-IP:   1.2.3.4   ` (leading/trailing whitespace from a misbehaving proxy)
- **THEN** `call.clientIp == "1.2.3.4"`

#### Scenario: Direct `X-Forwarded-For` reads forbidden
- **WHEN** searching `backend/ktor/src/main/**` for `request.headers["X-Forwarded-For"]` or `header("X-Forwarded-For")`
- **THEN** zero matches outside the `ClientIpExtractor` itself (CI lint rule enforces this — direct reads bypass the extractor's spoof-protection ladder)

### Requirement: Ktor intercept registered as a `RouteScopedPlugin`

The extractor SHALL be implemented as a Ktor `RouteScopedPlugin` (or equivalent application-level intercept on `Plugins` phase) registered before any other plugin that consumes `clientIp`. The plugin MUST:

- Run on every request that reaches the Ktor router (no path filter).
- Set the `clientIp` attribute exactly once per request.
- Be idempotent if invoked twice (e.g., during reverse proxy hop nesting) — the second invocation MUST observe the existing attribute and skip re-resolution.

The plugin MUST be installed in `Application.module()` before `installAuth(...)` and before any rate-limiter wiring.

#### Scenario: Plugin runs before auth
- **WHEN** examining `Application.module()` install order
- **THEN** the `ClientIpPlugin` install statement appears before `installAuth(...)` AND before any `RateLimiter` Koin binding consumption

#### Scenario: Plugin sets attribute exactly once
- **WHEN** a request flows through the plugin AND a downstream interceptor inspects `call.attributes`
- **THEN** the `ClientIp` attribute key is present exactly once (no duplicate keys)

### Requirement: Spoof-protection guidance

The extractor itself does NOT verify that the inbound request actually transited the Cloudflare edge — that defense lives at infrastructure level (Cloud Armor allowlist of Cloudflare IP ranges OR a Ktor middleware check that `X-Forwarded-For`'s last entry belongs to a known CF range). This change SHALL document the operator-facing guidance in code comments at the extractor implementation site (or in `docs/07-Operations.md` if a runbook entry exists).

The extractor MUST NOT silently trust `CF-Connecting-IP` from a request that bypasses Cloudflare in environments where Cloudflare is the canonical edge. For Phase 1 MVP without Cloud Armor, the operator-facing guidance is: rely on Cloud Run ingress=`internal-and-cloud-load-balancing` + Cloudflare DNS as the practical control until Cloud Armor wiring lands. Local dev / staging without Cloudflare in the ladder fall through to `X-Forwarded-For` or `remoteHost` as documented.

#### Scenario: Operator guidance documented
- **WHEN** reading the `ClientIpExtractor` (or `ClientIpPlugin`) source file
- **THEN** the file's KDoc / top-of-file comment cites [`docs/05-Implementation.md` § Cloudflare-Fronted IP Extraction § Spoof Protection](docs/05-Implementation.md) AND notes the Phase 1 vs Cloud-Armor-wired difference

### Requirement: Detekt rule `RawXForwardedForRule`

A Detekt rule under `:lint:detekt-rules` SHALL flag any read of `request.headers["X-Forwarded-For"]`, `call.request.header("X-Forwarded-For")`, or `call.request.headers.get("X-Forwarded-For")` outside the `ClientIpExtractor` source file. The rule's allow-list MUST include the extractor itself; everything else fails the lint with a message pointing at the canonical extractor.

The rule MUST be wired into the Detekt configuration so that `./gradlew :lint:detekt-rules:test` AND `./gradlew detekt` both run it on every CI build.

#### Scenario: Direct XFF read flagged
- **WHEN** a Kotlin file outside `ClientIpExtractor.kt` contains `call.request.header("X-Forwarded-For")`
- **THEN** the Detekt rule fails the build with a message naming the file and pointing to `ClientIpExtractor` as the canonical reader

#### Scenario: ClientIpExtractor itself is allow-listed
- **WHEN** the extractor's own source file reads `X-Forwarded-For` as part of the precedence ladder
- **THEN** the Detekt rule does NOT flag the read (the extractor is the single sanctioned consumer)

