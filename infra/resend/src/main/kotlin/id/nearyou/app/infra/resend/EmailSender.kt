package id.nearyou.app.infra.resend

/**
 * A rendered transactional-email template: a subject line plus the HTML and plain-text
 * bodies. Templates are versioned in-repo under `/backend/email-templates/` (spec
 * `transactional-email`); the caller renders one and hands it to [EmailSender.send].
 */
data class EmailTemplate(
    val subject: String,
    val htmlBody: String,
    val textBody: String,
)

/**
 * Outcome of an [EmailSender.send] call. Sealed so callers exhaustively handle every
 * branch — there is no exception path for the ordinary "send failed" case (fail-soft):
 *
 * - [Sent] — Resend accepted the message; [id] is the Resend message id (for audit).
 * - [Failed] — a non-retryable error or exhausted retries; [reason] is a NON-PII summary
 *   (HTTP status / category), never the recipient or body.
 * - [Skipped] — the sender is not configured (no API key); the no-op binding returns this
 *   so consumers degrade gracefully instead of crashing.
 */
sealed interface SendResult {
    data class Sent(val id: String) : SendResult

    data class Failed(val reason: String) : SendResult

    data object Skipped : SendResult
}

/**
 * Vendor-neutral transactional-email port (design D2). The single vendor implementation
 * ([ResendEmailSender]) lives in this module; `:backend:ktor` depends only on this
 * interface — no Resend/Ktor-client transport type leaks past `:infra:resend` (Resend
 * ships no official JVM SDK, so the impl uses a raw Ktor client).
 *
 * [isConfigured] reports whether the Resend API key is present; an unconfigured
 * environment fails soft ([NoOpEmailSender]) — boot succeeds and [send] returns
 * [SendResult.Skipped]. Callers MAY pre-gate on [isConfigured], OR rely on the fail-soft
 * behavior directly (the sole consumer takes the latter path: it calls [send] unconditionally
 * and handles the [SendResult.Skipped] branch, no pre-gate).
 */
interface EmailSender {
    fun isConfigured(): Boolean

    /**
     * Sends [template] to [to], carrying [idempotencyKey] as the Resend `Idempotency-Key`
     * header so a retried send with the same key delivers exactly once. Never throws for an
     * ordinary delivery failure — returns [SendResult.Failed]. MUST NOT log [to] or the body.
     */
    suspend fun send(
        to: String,
        template: EmailTemplate,
        idempotencyKey: String,
    ): SendResult
}
