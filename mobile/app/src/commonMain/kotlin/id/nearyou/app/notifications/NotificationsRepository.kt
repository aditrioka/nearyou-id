package id.nearyou.app.notifications

/**
 * Orchestrates a notifications first-page fetch: call `GET /api/v1/notifications` (NO cursor on the first
 * page) → map the HTTP **status** to exactly one [NotificationsOutcome] (design D8). There is no generic
 * "load failed" fallthrough; `401` is delegated to the shipped `Auth` plugin (this repository MUST NOT
 * reimplement token refresh / re-route). The unread-count / mark-read / mark-all-read pass-throughs back
 * the badge + the optimistic read mutations.
 *
 * The diagnostic sink ([diagnosticLog]) carries **only** the HTTP status and/or the outcome type — NEVER
 * `actor_user_id`, `target_id`, `body_data`, the response body, or any token (mirroring the shipped
 * `GlobalTimelineRepository` log discipline, design D8). It is wired to Sentry / OTel when that lands;
 * no-op for now.
 */
class NotificationsRepository(
    private val apiClient: NotificationsApiClient,
    private val diagnosticLog: (String) -> Unit = {},
) : NotificationsFlow {
    override suspend fun loadFirstPage(): NotificationsOutcome =
        when (val result = apiClient.fetch(cursor = null)) {
            is NotificationsApiResult.Success ->
                NotificationsOutcome.Loaded(
                    items = result.body.items,
                    nextCursor = result.body.nextCursor,
                )
            is NotificationsApiResult.NetworkError -> {
                // Type only — never the cause message / body / any PII.
                diagnosticLog("notifications_network_error: outcome=NetworkError")
                NotificationsOutcome.NetworkError
            }
            is NotificationsApiResult.HttpError ->
                when {
                    // 400 invalid_cursor — not expected from the always-valid first page; surface as
                    // retryable WITH a logged diagnostic (NOT a silent no-op, NOT a crash).
                    result.status == 400 -> {
                        diagnosticLog("notifications_invalid_request: status=400")
                        NotificationsOutcome.Error
                    }
                    // Terminal 401 (survived the shipped Auth refresh) → SessionExpired, NOT a
                    // retryable error — mirrors GlobalTimelineRepository (session-expiry D4;
                    // 2026-06-10 audit, finding 06-#3).
                    result.status == 401 -> {
                        diagnosticLog("notifications_session_expired: status=401")
                        NotificationsOutcome.SessionExpired
                    }
                    result.status in 500..599 -> {
                        diagnosticLog("notifications_server_error: status=${result.status}")
                        NotificationsOutcome.NetworkError
                    }
                    // 401 is handled upstream by the shipped Auth plugin + SessionInvalidator (terminal
                    // 401 → store cleared → RootRouterScreen re-routes to SignInScreen). Any other
                    // unenumerated status maps to the DEFINED retryable NetworkError state rather than a
                    // generic "load failed" fallthrough (mirrors GlobalTimelineRepository).
                    else -> {
                        diagnosticLog("notifications_unexpected_status: status=${result.status}")
                        NotificationsOutcome.NetworkError
                    }
                }
        }

    override suspend fun unreadCount(): Long? =
        when (val result = apiClient.unreadCount()) {
            is UnreadCountResult.Success -> result.count
            UnreadCountResult.Failure -> null
        }

    override suspend fun markRead(id: String): MarkReadResult = apiClient.markRead(id)

    override suspend fun markAllRead(): MarkAllReadResult = apiClient.markAllRead()
}
