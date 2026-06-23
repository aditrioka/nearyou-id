package id.nearyou.app.admin.wordlist

import id.nearyou.app.admin.auth.AdminAuditLogger
import id.nearyou.app.infra.remoteconfig.PublishResult
import id.nearyou.app.infra.remoteconfig.RemoteConfigPublisher
import id.nearyou.app.infra.remoteconfig.RemoteConfigStringList
import id.nearyou.app.infra.remoteconfig.publishServerStringList
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID
import javax.sql.DataSource

/**
 * Render + write orchestration for the moderation wordlist editor
 * (`admin-moderation-wordlist-editor`). Role + CSRF gating happen at the route;
 * this service owns entry normalization, the diff, the empty-list + no-op + cap
 * guards, the rate-limit + audit transaction, and the Server-template publish via
 * the [RemoteConfigPublisher] string-list seam. Mirrors
 * [id.nearyou.app.admin.featureflags.FeatureFlagService] (the sibling on the same
 * surface), so the gate ordering, the one-connection rate-limit+audit contract,
 * and the graceful read-only degradation all match.
 */
class WordlistEditorService(
    private val publisher: RemoteConfigPublisher,
    private val rateLimiter: WordlistEditRateLimiter,
    private val auditLogger: AdminAuditLogger,
    private val dataSource: DataSource,
) {
    /**
     * Current editor state for `GET /admin/feature-flags/wordlists/{list}`. When the
     * publisher is unconfigured OR the template read fails, returns a read-only
     * state (the route renders disabled controls + a notice — design D7).
     */
    fun viewState(target: WordlistTarget): WordlistViewState {
        if (!publisher.isConfigured()) return WordlistViewState.readOnly()
        val snapshot = publisher.fetchServerTemplate(setOf(target.parameterName)) ?: return WordlistViewState.readOnly()
        return WordlistViewState(
            writeAvailable = true,
            etag = snapshot.etag,
            version = snapshot.version,
            entries = RemoteConfigStringList.decode(snapshot.parameters[target.parameterName]) ?: emptyList(),
        )
    }

    /**
     * Compute a staged preview (merge entries + add-single + bulk import, normalize,
     * diff vs current) WITHOUT publishing or auditing. Drives the diff panel + the
     * import report on the editor page.
     */
    fun preview(
        target: WordlistTarget,
        entriesText: String,
        addText: String,
        importText: String,
    ): WordlistPreview {
        val state = viewState(target)
        if (!state.writeAvailable) {
            return WordlistPreview(state, state.entries, null, null, WRITE_UNAVAILABLE_MESSAGE)
        }
        return when (val staged = WordlistNormalizer.stage(entriesText, addText, importText)) {
            is StageResult.Invalid -> WordlistPreview(state, state.entries, null, null, staged.message)
            is StageResult.Valid -> {
                val diff = WordlistNormalizer.diff(state.entries, staged.resulting)
                WordlistPreview(state, staged.resulting, diff, staged.report, null)
            }
        }
    }

    /**
     * Apply one wordlist publish under all non-auth gates: reason → validation →
     * empty-list → no-op → (one connection) rate-limit → publish → audit. The acting
     * role + CSRF are already enforced at the route. The rate-limit COUNT and the
     * success-path audit INSERT run on ONE connection (design D2), held across the
     * REST publish (the FeatureFlagService precedent).
     */
    fun publish(request: WordlistEditRequest): WordlistEditOutcome {
        val reason = request.reason.trim()
        if (reason.isEmpty()) return WordlistEditOutcome.Invalid("A reason is required.")

        if (!publisher.isConfigured()) return WordlistEditOutcome.WriteUnavailable
        val snapshot =
            publisher.fetchServerTemplate(setOf(request.target.parameterName)) ?: return WordlistEditOutcome.WriteUnavailable
        val current = RemoteConfigStringList.decode(snapshot.parameters[request.target.parameterName]) ?: emptyList()

        val resulting =
            when (val staged = WordlistNormalizer.stage(request.entriesText, request.addText, request.importText)) {
                is StageResult.Invalid -> return WordlistEditOutcome.Invalid(staged.message)
                is StageResult.Valid -> staged.resulting
            }

        // Empty-list guard (design D4): an empty array is loader-failure (fallback +
        // Sentry WARN) — never a path to "moderation off".
        if (resulting.isEmpty()) return WordlistEditOutcome.Invalid(EMPTY_LIST_MESSAGE)

        val diff = WordlistNormalizer.diff(current, resulting)
        if (diff.isNoOp) return WordlistEditOutcome.NoOp

        return dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                if (rateLimiter.isAtOrOverCap(conn, request.actingAdminId)) {
                    conn.rollback()
                    WordlistEditOutcome.RateLimited
                } else {
                    when (
                        val result =
                            publisher.publishServerStringList(request.target.parameterName, resulting, request.expectedEtag)
                    ) {
                        is PublishResult.Published -> {
                            auditLogger.logModerationWordlistEdited(
                                conn = conn,
                                adminId = request.actingAdminId,
                                listParameterName = request.target.parameterName,
                                reason = reason,
                                beforeState = beforeState(request.target, current, snapshot.version),
                                afterState = afterState(request.target, current, resulting, diff, result.newEtag),
                                ip = request.ip,
                                userAgent = request.userAgent,
                            )
                            conn.commit()
                            WordlistEditOutcome.Applied(diff)
                        }
                        PublishResult.StaleVersion -> {
                            conn.rollback()
                            WordlistEditOutcome.Stale
                        }
                        PublishResult.WriteUnavailable -> {
                            conn.rollback()
                            WordlistEditOutcome.WriteUnavailable
                        }
                        is PublishResult.Failed -> {
                            conn.rollback()
                            WordlistEditOutcome.Failed
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

    /** The acting admin's wordlist publishes in the trailing hour (for the editor quota chip; reads only). */
    fun quotaUsed(adminId: UUID): Int = rateLimiter.countInTrailingHour(adminId)

    /** Diff-summary `before_state` (counts + version pointer — design D3, NOT the full array). */
    private fun beforeState(
        target: WordlistTarget,
        current: List<String>,
        version: String?,
    ): JsonElement =
        buildJsonObject {
            put("list", target.parameterName)
            put("count", current.size)
            version?.let { put("version", it) }
        }

    /** Diff-summary `after_state`: counts + added/removed + bounded changed-entry samples (design D3). */
    private fun afterState(
        target: WordlistTarget,
        current: List<String>,
        resulting: List<String>,
        diff: WordlistDiff,
        newEtag: String?,
    ): JsonElement {
        val currentSet = current.toSet()
        val resultingSet = resulting.toSet()
        return buildJsonObject {
            put("list", target.parameterName)
            put("count", resulting.size)
            put("added", diff.added)
            put("removed", diff.removed)
            newEtag?.let { put("etag", it) }
            putJsonArray("added_sample") {
                resulting.filter { it !in currentSet }.take(AUDIT_SAMPLE).forEach { add(it) }
            }
            putJsonArray("removed_sample") {
                current.filter { it !in resultingSet }.take(AUDIT_SAMPLE).forEach { add(it) }
            }
        }
    }

    companion object {
        /** Max changed entries retained inline in the audit diff-summary samples (design D3, Q1). */
        const val AUDIT_SAMPLE: Int = 20

        const val WRITE_UNAVAILABLE_MESSAGE: String =
            "Writes unavailable — the Remote Config write role is not configured on this admin service."

        const val EMPTY_LIST_MESSAGE: String =
            "A wordlist cannot be emptied — an empty list disables this moderation layer (it falls back + alerts). " +
                "To disable the layer, use its kill-switch flag instead. No change made."
    }
}

/** Inputs for a single wordlist publish (assembled at the route after the gates). */
data class WordlistEditRequest(
    val target: WordlistTarget,
    val entriesText: String,
    val addText: String,
    val importText: String,
    val reason: String,
    val expectedEtag: String,
    val actingAdminId: UUID,
    val ip: String,
    val userAgent: String?,
)

/** Current editor render state. [writeAvailable] false → read-only render. */
data class WordlistViewState(
    val writeAvailable: Boolean,
    val etag: String?,
    val version: String?,
    val entries: List<String>,
) {
    companion object {
        fun readOnly(): WordlistViewState = WordlistViewState(writeAvailable = false, etag = null, version = null, entries = emptyList())
    }
}

/** A staged preview: the merged [staged] list, the [diff] vs current, the import [report], or a [message]. */
data class WordlistPreview(
    val viewState: WordlistViewState,
    val staged: List<String>,
    val diff: WordlistDiff?,
    val report: StagingReport?,
    val message: String?,
)

/** Outcome of a wordlist publish. The route maps each to a user-facing inline message. */
sealed interface WordlistEditOutcome {
    data class Applied(val diff: WordlistDiff) : WordlistEditOutcome

    data class Invalid(val message: String) : WordlistEditOutcome

    data object NoOp : WordlistEditOutcome

    data object RateLimited : WordlistEditOutcome

    data object Stale : WordlistEditOutcome

    data object WriteUnavailable : WordlistEditOutcome

    data object Failed : WordlistEditOutcome
}
