package id.nearyou.app.config

/**
 * Minimal Firebase Remote Config abstraction.
 *
 * The first consumer is the `premium_like_cap_override` integer flag read on every
 * `POST /api/v1/posts/{post_id}/like` Free-tier request (see
 * `openspec/changes/like-rate-limit/specs/post-likes/spec.md` § "Requirement:
 * `premium_like_cap_override` Firebase Remote Config flag"). Flag semantics are
 * deliberately conservative — any failure mode (unset / malformed / SDK error /
 * network timeout) MUST fall through to the canonical default at the call site.
 *
 * The interface returns a typed [Long] (rather than the raw `String` Firebase
 * SDKs use natively) so call sites don't each re-implement string parsing. A
 * non-integer-parseable value at the SDK side surfaces here as `null`, identical
 * to the unset case — coercion of `0` / negative integers to a default is a
 * call-site decision (the override semantics are scope-specific).
 *
 * Production wires this against the Firebase Remote Config Admin SDK once the
 * Phase 1 rollout adds the dependency. The current binding is [StubRemoteConfig]:
 * always returns `null` (use the default), which is the safe fallback path until
 * the Firebase SDK lands.
 */
interface RemoteConfig {
    /**
     * Returns the integer value of [key], or `null` if:
     *  - the flag is unset;
     *  - the flag is set but does not parse as a `Long` (malformed string);
     *  - the SDK throws an `IOException` / `RuntimeException` (network failure,
     *    auth failure, etc.) — implementations MUST catch and return `null`,
     *    NOT propagate the throwable; user-facing 5xx from a Remote Config error
     *    is a worse failure mode than falling back to the conservative default.
     */
    fun getLong(key: String): Long?
}

/**
 * Default no-op binding: always returns `null` so every call site falls through
 * to its scope-specific default. This is the production binding until the
 * Firebase Remote Config Admin SDK is integrated.
 *
 * Tests that need a non-null value inject a fake [RemoteConfig] directly into
 * the service under test (mirroring the V9 `ReportRateLimiter` test-double
 * precedent — Koin-aware tests override the binding via a test module).
 */
class StubRemoteConfig : RemoteConfig {
    override fun getLong(key: String): Long? = null
}
