## ADDED Requirements

### Requirement: Global request body size limit

`Application.module()` SHALL install Ktor's `RequestBodyLimit` plugin (`io.ktor:ktor-server-body-limit`, at the pinned Ktor version) application-wide, so that no route buffers an unbounded request body. The limit SHALL be resolved per request from the request path by ONE resolver function:

- `/api/v1/images` → the 5 MiB image cap (`MAX_IMAGE_BYTES`) plus 64 KiB of multipart-envelope headroom, so the route's own streamed `413 image_too_large` guard stays authoritative for the file part;
- `/admin/reserved-usernames/bulk` → 1 MiB, above the form-encoded size of that route's 256 KB CSV guard, so its in-band `400` stays authoritative;
- every path under `/admin/feature-flags/wordlists/` → 4 MiB (the editor re-posts the full staged list, bounded by the 10 000-entry × 100-character wordlist caps);
- every other path → 64 KiB.

A request whose declared `Content-Length` exceeds the resolved limit SHALL be rejected `413 Payload Too Large` with the StatusPages envelope `{"error": {"code": "payload_too_large", "message": "Payload too large"}}` BEFORE authentication, route handlers, or deserialisation run. A request without a `Content-Length` (chunked transfer) SHALL be cut off as soon as the bytes read exceed the limit and rejected with the same `413` envelope — the body is never fully buffered.

Existing route-level guards (the 4 KiB `contentLength()` checks on the user-settings `PATCH` routes, the 64 KiB guards on the RevenueCat and CSAM webhooks, the image route's streamed cap, the reserved-usernames 256 KB guard) remain in place and stay authoritative for bodies within the path's transport limit; a body above the transport limit is rejected `413 payload_too_large` by this plugin regardless of the route's own oversize status.

A new override SHALL be added only in the single resolver function (one canonical body-size cap — `docs/11` §3.3), never by a second `RequestBodyLimit` installation or a route-local uncapped raw-body read.

#### Scenario: Oversize JSON body with Content-Length is rejected before authentication and deserialisation
- **WHEN** an unauthenticated `POST /api/v1/posts` declares `Content-Length: 1048576` (1 MiB)
- **THEN** the response status is `413` with error code `payload_too_large` AND no `401` authentication challenge is returned AND the route handler and its JSON deserialiser never run

#### Scenario: Oversize chunked body is cut off and rejected
- **WHEN** a `POST /api/v1/posts` streams 1 MiB with no `Content-Length` header (chunked)
- **THEN** the response status is `413` with error code `payload_too_large` AND deserialisation never completes

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
