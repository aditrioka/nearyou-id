package id.nearyou.app.infra.sentry

/**
 * mobile-crash-reporting — the vendor-SDK-FREE crash + error reporting seam.
 *
 * `:mobile:app` depends on this interface; the Sentry KMP SDK is fenced to the platform
 * `SentryCrashReporter` actuals in this same module (CLAUDE.md invariant #16 — no vendor SDK
 * import outside `:infra:*`), `implementation`-scoped so the vendor symbol never reaches the
 * app's compile classpath. The Open Decision #17 fallback (Crashlytics-Android + Sentry-Cocoa-iOS
 * dual pipeline) would be a drop-in alternate impl behind this same interface.
 *
 * All methods are synchronous fire-and-forget (the underlying native SDKs queue + flush off-thread).
 */
interface CrashReporter {
    /**
     * Initialize the reporter. Idempotent enough to call again after [close] (re-init on re-consent).
     * Implementations MUST set `sendDefaultPii = false` (no client-IP `{{auto}}` inference, no
     * `server_name`/device name) and install a `beforeSend` PII-scrubbing hook — see
     * mobile-crash-reporting spec § "Crash payloads are scrubbed of PII".
     */
    fun init(config: CrashReporterConfig)

    /** Report a handled/unhandled throwable with optional pre-redacted string context. */
    fun captureException(
        throwable: Throwable,
        context: Map<String, String> = emptyMap(),
    )

    /** Correlate subsequent events to the signed-in user via an OPAQUE id only (the JWT `sub`). */
    fun setUser(id: String)

    /** Clear user correlation on logout. */
    fun clearUser()

    /** Record a coordinate-free breadcrumb (see [Breadcrumb]). */
    fun addBreadcrumb(breadcrumb: Breadcrumb)

    /** Stop reporting for the session (crash-consent decline). Safe to call when not initialized. */
    fun close()
}

/**
 * Init config. [dsn] is a write-only client-embeddable Sentry ingest key (NOT a secret — matches
 * the "slot names in source, secrets in Secret Manager" posture); a blank [dsn] MUST no-op init.
 * [environment] is the build flavor (`dev`/`staging`/`production`); [release] is the app version.
 */
data class CrashReporterConfig(
    val dsn: String,
    val environment: String,
    val release: String,
)

/**
 * A coordinate-free breadcrumb. Content MUST carry only pre-redacted status/outcome strings —
 * never a coordinate or token (mirrors the `DiagnosticSink` contract it is fed from).
 */
data class Breadcrumb(
    val message: String,
    val category: String? = null,
    val level: BreadcrumbLevel = BreadcrumbLevel.INFO,
)

enum class BreadcrumbLevel { DEBUG, INFO, WARNING, ERROR }

/**
 * The default no-op reporter: bound when the DSN is blank or crash consent is declined, and used
 * as the test/headless default. Every method is a safe no-op.
 */
object NoOpCrashReporter : CrashReporter {
    override fun init(config: CrashReporterConfig) = Unit

    override fun captureException(
        throwable: Throwable,
        context: Map<String, String>,
    ) = Unit

    override fun setUser(id: String) = Unit

    override fun clearUser() = Unit

    override fun addBreadcrumb(breadcrumb: Breadcrumb) = Unit

    override fun close() = Unit
}
