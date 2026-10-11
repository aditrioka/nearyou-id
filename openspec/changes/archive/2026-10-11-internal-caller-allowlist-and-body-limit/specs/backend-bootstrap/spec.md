## ADDED Requirements

### Requirement: Global request body size limit

`Application.module()` SHALL install Ktor's `RequestBodyLimit` plugin (`io.ktor:ktor-server-body-limit`, at the pinned Ktor version) application-wide, so that no route buffers an unbounded request body. The limit SHALL be resolved per request from the request path by ONE resolver function:

- `/api/v1/images` → the 5 MiB image cap (`MAX_IMAGE_BYTES`) plus 64 KiB of multipart-envelope headroom, so the route's own streamed `413 image_too_large` guard stays authoritative for the file part;
- `/admin/reserved-usernames/bulk` → 1 MiB, above the form-encoded size of that route's 256 KB CSV guard, so its in-band `400` stays authoritative;
- every path under `/admin/feature-flags/wordlists/` → 4 MiB (the editor re-posts the full staged list, bounded by the 10 000-entry × 100-character wordlist caps);
- every other path → 64 KiB.

A request whose declared `Content-Length` exceeds the resolved limit SHALL be rejected `413 Payload Too Large` with the StatusPages envelope `{"error": {"code": "payload_too_large", "message": "Payload too large"}}` BEFORE authentication, route handlers, or deserialisation run.

A request without a `Content-Length` (chunked transfer) SHALL be cut off as soon as the bytes read exceed the resolved limit — the body is never buffered beyond the limit — and SHALL be answered with a `4xx`, never a `5xx`: `413 payload_too_large` where the limiter's `PayloadTooLargeException` reaches StatusPages (raw byte/text reads), otherwise `400` — the `invalid_request` envelope when the JSON deserialiser drops the cause (ContentNegotiation reports it as `CannotTransformContentToTypeException`, which StatusPages maps to `400`), or the route's own malformed-body response when the route handles receive failures itself.

Request-body decompression SHALL be disabled (`Compression` installed in response-only mode): the limiter counts the raw bytes on the wire, so inflating a `Content-Encoding: gzip` body after it would let a small compressed body expand far past the cap. A compressed request body reaches the route as its raw bytes.

Existing route-level guards (the 4 KiB `contentLength()` checks on the user-settings `PATCH` routes and `POST /api/v1/user/fcm-token`, the 64 KiB guards on the RevenueCat and CSAM webhooks — equal to the default, kept as belt-and-braces — the image route's streamed cap, the reserved-usernames 256 KB guard) remain in place and stay authoritative for bodies within the path's transport limit; a body above the transport limit is rejected by this plugin regardless of the route's own oversize status.

A path that needs MORE than the 64 KiB default SHALL get its override in the single resolver function, never by a second `RequestBodyLimit` installation (the application-level `Content-Length` pre-check runs before routing, so a route-level install cannot raise it). A route that needs LESS keeps its own tighter guard.

#### Scenario: Oversize JSON body with Content-Length is rejected before authentication and deserialisation
- **WHEN** an unauthenticated `POST /api/v1/posts` declares `Content-Length: 1048576` (1 MiB)
- **THEN** the response status is `413` with error code `payload_too_large` AND no `401` authentication challenge is returned AND the route handler and its JSON deserialiser never run

#### Scenario: Oversize chunked JSON body is cut off with a 4xx, never a 5xx
- **WHEN** a `POST` to a JSON route that lets receive failures propagate streams 1 MiB with no `Content-Length` header (chunked)
- **THEN** the response status is `400` with error code `invalid_request` (never `5xx`) AND deserialisation never completes AND the body is not buffered beyond 64 KiB

#### Scenario: Oversize chunked raw body surfaces the limiter's 413
- **WHEN** a raw-byte route reads a chunked body that crosses its resolved limit
- **THEN** the response status is `413` with error code `payload_too_large` AND the handler never completes

#### Scenario: A gzip request body is not inflated past the cap
- **WHEN** a `POST` declares `Content-Encoding: gzip` with a small compressed body that would expand to 4 MiB
- **THEN** the route receives the raw compressed bytes (no server-side request decompression), so nothing beyond the path's limit is ever buffered

#### Scenario: Body at the default limit is accepted
- **WHEN** a `POST` to a default-limit path carries a body of exactly 64 KiB
- **THEN** the plugin does not reject it and the route handler runs

#### Scenario: Image upload path admits a full-size image upload
- **WHEN** a `POST /api/v1/images` carries a multipart body of 5 MiB of image bytes plus its envelope (total ≤ 5 MiB + 64 KiB)
- **THEN** the plugin does not reject it and the route's own guards decide the outcome

#### Scenario: Image upload path rejects bodies beyond its cap
- **WHEN** a `POST /api/v1/images` declares a `Content-Length` above 5 MiB + 64 KiB
- **THEN** the response status is `413` with error code `payload_too_large` before the route handler runs

#### Scenario: Admin bulk paths admit bodies above the default limit
- **WHEN** a `POST /admin/reserved-usernames/bulk` carries 512 KiB OR a `POST /admin/feature-flags/wordlists/profanity` carries 2 MiB
- **THEN** the plugin does not reject either request and the routes' own guards decide the outcome

#### Scenario: Path overrides do not leak to sibling paths
- **WHEN** a request to `/api/v1/images-x`, `/admin/reserved-usernames`, or `/admin/feature-flags` declares a `Content-Length` of 1 MiB
- **THEN** each is rejected `413 payload_too_large` (the 64 KiB default applies)
