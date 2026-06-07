package id.nearyou.app.notifications

import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Test-only [NotificationsFlow] for screen / ViewModel / shell tests (mirrors `FakeGlobalTimelineFlow`).
 * Returns a pre-programmed [NotificationsOutcome] + unread count + mark-read / mark-all-read results, and
 * counts invocations so a test can assert (re-)invocation. With [suspendForever] = true `loadFirstPage`
 * never returns (the screen stays in-flight → the Loading state is assertable). With [failWith] set,
 * `loadFirstPage` throws it after counting (the VM maps that to the retryable error state).
 *
 * [markReadResult] / [markReadThrows] drive the optimistic-mark-read paths (204 keep / 404 keep / explicit
 * Failed revert / transport-throw revert); [markAllReadResult] drives mark-all-read (success keep / Failed
 * revert).
 */
class FakeNotificationsFlow(
    private val outcome: NotificationsOutcome = NotificationsOutcome.Loaded(emptyList(), null),
    private val suspendForever: Boolean = false,
    private val failWith: Throwable? = null,
    private val unreadCountValue: Long? = 0L,
    private val markReadResult: MarkReadResult = MarkReadResult.Acknowledged,
    private val markReadThrows: Throwable? = null,
    private val markAllReadResult: MarkAllReadResult = MarkAllReadResult.Success(0),
) : NotificationsFlow {
    var loadInvocationCount: Int = 0
        private set

    var unreadCountInvocationCount: Int = 0
        private set

    val markReadIds: MutableList<String> = mutableListOf()

    var markAllReadInvocationCount: Int = 0
        private set

    override suspend fun loadFirstPage(): NotificationsOutcome {
        loadInvocationCount++
        if (suspendForever) awaitCancellation()
        failWith?.let { throw it }
        return outcome
    }

    override suspend fun unreadCount(): Long? {
        unreadCountInvocationCount++
        return unreadCountValue
    }

    override suspend fun markRead(id: String): MarkReadResult {
        markReadIds.add(id)
        markReadThrows?.let { throw it }
        return markReadResult
    }

    override suspend fun markAllRead(): MarkAllReadResult {
        markAllReadInvocationCount++
        return markAllReadResult
    }
}

/** Shared fixture: a fully-populated notification (incl. the PII fields + a `post_excerpt` body_data) for
 *  projection / row-render tests. Defaults to an UNREAD `post_liked` with the canonical excerpt. */
fun fakeNotification(
    id: String = "n1",
    type: String = "post_liked",
    actorUserId: String? = "11111111-1111-1111-1111-111111111111",
    targetType: String? = "post",
    targetId: String? = "22222222-2222-2222-2222-222222222222",
    bodyData: JsonElement = buildJsonObject { put("post_excerpt", "halo dunia") },
    createdAt: String = "2026-05-31T10:00:00Z",
    readAt: String? = null,
): NotificationDto =
    NotificationDto(
        id = id,
        type = type,
        actorUserId = actorUserId,
        targetType = targetType,
        targetId = targetId,
        bodyData = bodyData,
        createdAt = createdAt,
        readAt = readAt,
    )
