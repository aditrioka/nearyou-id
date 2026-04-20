## ADDED Requirements

### Requirement: ContentLengthGuard middleware with per-route limits registry

`Application.module()` SHALL install a `ContentLengthGuard` Ktor plugin that consults a registry mapping a route key (e.g., `"post.content"`) to a maximum code-point length. This change MUST register exactly one entry — `"post.content" → 280` — and wire the guard to the `POST /api/v1/posts` route. Future content-bearing endpoints register their own entries without modifying the plugin.

The guard MUST:
1. NFKC-normalize the incoming string.
2. Trim leading/trailing whitespace.
3. Reject empty (post-trim) with HTTP 400 code `content_empty`.
4. Reject code-point length > the registered limit with HTTP 400 code `content_too_long`.

#### Scenario: Plugin installed at startup
- **WHEN** the server starts
- **THEN** the startup log shows installation of `ContentLengthGuard` (or equivalent log line) without exception

#### Scenario: post.content limit registered
- **WHEN** inspecting the registry after startup
- **THEN** the registry contains an entry `("post.content", 280)`

#### Scenario: Registry-driven enforcement on post creation
- **WHEN** a `POST /api/v1/posts` request carries a 281-code-point `content`
- **THEN** the guard rejects with HTTP 400 `content_too_long` before the route handler runs

### Requirement: Post creation route wired in Application.module()

`Application.module()` SHALL register the `POST /api/v1/posts` route inside `authenticate { ... }` so the Ktor `Authentication` plugin runs before the route handler.

#### Scenario: Route is authenticated
- **WHEN** `POST /api/v1/posts` is hit without an `Authorization` header
- **THEN** the response is HTTP 401 (from the `Authentication` plugin, not the route handler)
