package id.nearyou.app.config

import io.ktor.client.engine.HttpClientEngine

/**
 * Platform Ktor engine — `OkHttp` on Android, `Darwin` on iOS. Returns a constructed
 * [HttpClientEngine] (not a factory) so `HttpClientFactory.create` takes the idiomatic
 * Ktor test seam: production passes this; commonTest passes a `MockEngine { … }` instance.
 * (Minor deviation from `tasks.md` 5.6's `HttpClientEngineFactory<*>` wording — the
 * pre-built-engine form is the canonical MockEngine-injection pattern.)
 */
expect fun httpClientEngine(): HttpClientEngine

/**
 * Whether this is a debug build. Gates the Ktor `Logging` plugin install (it MUST NOT be
 * installed in release builds per `openspec/specs/mobile-auth-signin/spec.md` §
 * "Authorization header is sanitized in Ktor Logging plugin output").
 */
expect val isDebugBuild: Boolean
