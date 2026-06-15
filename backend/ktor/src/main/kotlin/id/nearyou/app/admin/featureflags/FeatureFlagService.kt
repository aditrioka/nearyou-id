package id.nearyou.app.admin.featureflags

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.infra.remoteconfig.PublishResult
import id.nearyou.app.infra.remoteconfig.RemoteConfigPublisher
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import javax.sql.DataSource

/**
 * Render + write orchestration for the Feature Flag Admin panel
 * (`admin-feature-flags` capability). Role + CSRF gating happen at the route; this
 * service owns the value validation, the no-op guard, the rate-limit + audit
 * transaction, and the Server-template publish via the
 * [RemoteConfigPublisher] seam.
 */
class FeatureFlagService(
    private val publisher: RemoteConfigPublisher,
    private val rateLimiter: FeatureFlagToggleRateLimiter,
    private val auditLogger: AdminAuditLogger,
    private val dataSource: DataSource,
) {
    /**
     * Current editor state for `GET /admin/feature-flags`. When the publisher is
     * not configured (no write credentials) OR the template read fails, returns a
     * read-only state with no values — the route renders disabled controls + a
     * notice (spec Req "The panel degrades to read-only when Remote Config write
     * credentials are absent").
     */
    fun viewState(): FeatureFlagViewState {
        if (!publisher.isConfigured()) return FeatureFlagViewState.readOnly()
        val snapshot =
            publisher.fetchServerTemplate(FeatureFlagCatalog.ALL_PARAMETER_NAMES)
                ?: return FeatureFlagViewState.readOnly()
        return FeatureFlagViewState(
            writeAvailable = true,
            etag = snapshot.etag,
            version = snapshot.version,
            values = snapshot.parameters,
        )
    }

    /**
     * Apply one flag write under all non-auth gates: value validation → no-op
     * guard → (one connection) rate-limit → publish → audit. The acting role +
     * CSRF are already enforced at the route. The rate-limit COUNT and the
     * success-path audit INSERT run on ONE connection (design D3); the connection
     * is held across the REST publish — acceptable for the low-traffic admin path,
     * and the literal realization of the "one connection" contract.
     */
    fun toggle(request: ToggleRequest): ToggleOutcome {
        val reason = request.reason.trim()
        if (reason.isEmpty()) return ToggleOutcome.Invalid("A reason is required.")

        val normalized =
            when (val validation = FeatureFlagCatalog.validate(request.key, request.rawValue)) {
                is ValueValidation.Valid -> validation.normalized
                is ValueValidation.Invalid -> return ToggleOutcome.Invalid(validation.message)
            }

        if (!publisher.isConfigured()) return ToggleOutcome.WriteUnavailable

        // Current value, for the no-op guard + the audit before-state.
        val snapshot = publisher.fetchServerTemplate(setOf(request.key)) ?: return ToggleOutcome.WriteUnavailable
        val currentValue = snapshot.parameters[request.key]
        if (currentValue == normalized) return ToggleOutcome.NoOp

        return dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                if (rateLimiter.isAtOrOverCap(conn, request.actingAdminId)) {
                    conn.rollback()
                    ToggleOutcome.RateLimited
                } else {
                    when (val result = publisher.publishServerParameter(request.key, normalized, request.expectedEtag)) {
                        is PublishResult.Published -> {
                            auditLogger.logFeatureFlagToggled(
                                conn = conn,
                                adminId = request.actingAdminId,
                                parameterName = request.key,
                                reason = reason,
                                beforeState = valueJson(currentValue),
                                afterState = valueJson(normalized),
                                ip = request.ip,
                                userAgent = request.userAgent,
                            )
                            conn.commit()
                            ToggleOutcome.Applied
                        }
                        PublishResult.StaleVersion -> {
                            conn.rollback()
                            ToggleOutcome.Stale
                        }
                        PublishResult.WriteUnavailable -> {
                            conn.rollback()
                            ToggleOutcome.WriteUnavailable
                        }
                        is PublishResult.Failed -> {
                            conn.rollback()
                            ToggleOutcome.Failed
                        }
                    }
                }
            } catch (t: Throwable) {
                runCatching { conn.rollback() }
                throw t
            } finally {
                runCatching { conn.autoCommit = previousAutoCommit }
            }
        }
    }

    private fun valueJson(raw: String?) =
        buildJsonObject {
            put("value", if (raw == null) JsonNull else JsonPrimitive(raw))
        }
}

/** Inputs for a single flag write (assembled at the route after the gates). */
data class ToggleRequest(
    val key: String,
    val rawValue: String,
    val reason: String,
    val expectedEtag: String,
    val actingAdminId: UUID,
    val ip: String,
    val userAgent: String?,
)

/** Current editor render state. [writeAvailable] false → read-only render. */
data class FeatureFlagViewState(
    val writeAvailable: Boolean,
    val etag: String?,
    val version: String?,
    val values: Map<String, String?>,
) {
    companion object {
        fun readOnly(): FeatureFlagViewState =
            FeatureFlagViewState(
                writeAvailable = false,
                etag = null,
                version = null,
                values = emptyMap(),
            )
    }
}

/** Outcome of a flag write. The route maps each to a user-facing inline message. */
sealed interface ToggleOutcome {
    data object Applied : ToggleOutcome

    data class Invalid(val message: String) : ToggleOutcome

    data object NoOp : ToggleOutcome

    data object RateLimited : ToggleOutcome

    data object Stale : ToggleOutcome

    data object WriteUnavailable : ToggleOutcome

    data object Failed : ToggleOutcome
}
