package id.nearyou.app.notifications

/**
 * The notifications orchestration contract consumed by `NotificationsScreen` (+ the shell's unread
 * badge). The production binding is [NotificationsRepository]; commonTest substitutes a
 * `FakeNotificationsFlow` so the screen / ViewModel / shell tests can drive a specific outcome and
 * assert (re-)invocation without a backend — mirroring the `GlobalTimelineFlow` seam.
 */
interface NotificationsFlow {
    /**
     * Loads the first page of the notifications feed. Pull-to-refresh + error-retry re-invoke this; the
     * returned [NotificationsOutcome.Loaded.nextCursor] is retained but NOT consumed for load-more
     * (infinite scroll deferred alongside `mobile-nearby-timeline-infinite-scroll`).
     */
    suspend fun loadFirstPage(): NotificationsOutcome

    /**
     * The unread-count badge source (`GET /api/v1/notifications/unread-count`). Returns the count on
     * success or `null` on any failure (the badge is advisory + one-shot — a failed fetch simply shows
     * nothing, never a blocking error).
     */
    suspend fun unreadCount(): Long?

    /** Marks one notification read (`PATCH /{id}/read`). The caller keeps the optimistic flip on
     *  [MarkReadResult.Acknowledged]/[MarkReadResult.NotFound] and reverts on [MarkReadResult.Failed]. */
    suspend fun markRead(id: String): MarkReadResult

    /** Marks all loaded notifications read (`PATCH /read-all`). On [MarkAllReadResult.Success] the caller
     *  flips every loaded row to read; on [MarkAllReadResult.Failed] it reverts. */
    suspend fun markAllRead(): MarkAllReadResult
}

/**
 * Every notifications-list fetch maps to EXACTLY one member, keyed on the HTTP **status** /
 * transport-failure type — NOT on a parsed `error.code` — with no generic "load failed" fallthrough
 * (design D8). `401` is delegated to the shipped `Auth` plugin (terminal 401 → store cleared → re-route
 * to sign-in), NEVER mapped here. There are NO rate-limit states (the notifications read endpoint carries
 * no per-endpoint rate limit / `upsell` on the wire).
 */
sealed interface NotificationsOutcome {
    /**
     * HTTP 200. Carries the raw parsed [items] (incl. the `actorUserId` / `targetId` fields that the
     * UI-state projection STRIPS — PII never reaches the rendered state) and the opaque [nextCursor]
     * (retained, not yet used for load-more).
     */
    data class Loaded(
        val items: List<NotificationDto>,
        val nextCursor: String?,
    ) : NotificationsOutcome

    /** HTTP 5xx, transport/IO failure, or any unenumerated status — retryable. */
    data object NetworkError : NotificationsOutcome

    /** HTTP 400 (`invalid_cursor` — not expected on the always-valid first page) — retryable, logged. */
    data object Error : NotificationsOutcome

    /**
     * Terminal 401 (survived the shipped Auth-plugin refresh — the session is dead). Maps to the
     * neutral redirect placeholder, NOT a retryable error: the `SessionInvalidator` re-route is
     * already in flight. Added 2026-06-10 (audit finding 06-#3 — this feature had forked from the
     * timelines' session-expiry D4 pattern and showed the "check your connection" banner instead).
     */
    data object SessionExpired : NotificationsOutcome
}
