package id.nearyou.app.notifications

/**
 * The notifications orchestration contract consumed by `NotificationsScreen` (+ the shell's unread
 * badge). The production binding is [NotificationsRepository]; commonTest substitutes a
 * `FakeNotificationsFlow` so the screen / ViewModel / shell tests can drive a specific outcome and
 * assert (re-)invocation without a backend — mirroring the `GlobalTimelineFlow` seam.
 */
interface NotificationsFlow {
    /**
     * Loads the first page of the notifications feed (with the default `unread=false` filter — the full
     * inbox). Pull-to-refresh + error-retry re-invoke this; the returned
     * [NotificationsOutcome.Loaded.nextCursor] drives [loadMore] (cursor infinite scroll,
     * `mobile-nearby-timeline-infinite-scroll` extended to notifications).
     */
    suspend fun loadFirstPage(): NotificationsOutcome

    /**
     * Loads a subsequent page for [cursor] — the opaque `next_cursor` from the prior page, sent back
     * verbatim — reusing the SAME `unread` filter the first page used (`unread=false`, the full inbox), so
     * paging never narrows mid-scroll. Same status→outcome mapping as [loadFirstPage]; there is no spatial
     * anchor (unlike the Nearby feed) and no rate-limit / `upsell` state.
     */
    suspend fun loadMore(cursor: String): NotificationsOutcome

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

    /**
     * Resolves a `post`-target notification to the post-detail nav payload via the full-projection
     * `single-post-read` (`mobile-notifications-deep-link-targets`). [PostTargetResolution.Resolved] carries
     * the display fields the caller maps to a `PostDetailTarget` (`distanceM = null`); [Unavailable] (the
     * endpoint's single `404 post_not_found` / any failure) → the caller surfaces a non-blocking
     * "tidak tersedia" affordance and does NOT navigate.
     */
    suspend fun resolvePostTarget(postId: String): PostTargetResolution

    /**
     * Resolves a `chat_message` notification's partner display identity via `user-profile-read` — the
     * sender of a 1:1 message IS the partner, so the notification's `actor_user_id` is the partner.
     * [PartnerResolution.Resolved] gives the thread top-bar identity; on [Unavailable] the caller still
     * opens the thread with blank partner fields (the conversation is independently valid; the top bar
     * degrades to its existing placeholder).
     */
    suspend fun resolvePartner(userId: String): PartnerResolution
}

/**
 * Neutral resolution of a `post`-target deep-link (NOT a screens type — the data seam stays free of
 * `PostDetailTarget`; the ViewModel maps [Resolved] → `PostDetailTarget(distanceM = null, …)`). No author
 * UUID / coordinate is carried (the by-id projection has none — no-PII).
 */
sealed interface PostTargetResolution {
    data class Resolved(
        val postId: String,
        val authorUsername: String,
        val authorDisplayName: String,
        val content: String,
        val cityName: String,
        val createdAtIso: String,
        val likedByViewer: Boolean,
        val replyCount: Int,
        // image-attached-posts (#388): the post's public image URL from the by-id read, or null for a
        // text-only post. NO default — every mapper states it explicitly (a silent default-null is how
        // the notification deep-link lost the image in the first place).
        val imageUrl: String?,
    ) : PostTargetResolution

    data object Unavailable : PostTargetResolution
}

/** Neutral resolution of a chat partner's DISPLAY identity (no user UUID — display strings only). */
sealed interface PartnerResolution {
    data class Resolved(
        val username: String,
        val displayName: String,
    ) : PartnerResolution

    data object Unavailable : PartnerResolution
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
     * (the load-more cursor; `null` ⇒ end-reached).
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
