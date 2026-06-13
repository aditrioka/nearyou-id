package id.nearyou.app.subscription

import id.nearyou.app.notifications.NotificationEmitter
import id.nearyou.data.repository.NotificationDispatcher
import id.nearyou.data.repository.NotificationType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * RevenueCat billing event → subscription-status state machine
 * (`subscription-billing-webhook` capability). Each event is processed in ONE
 * transaction: status apply (read-free `UPDATE users ... RETURNING`, design D6)
 * → idempotency gate (`subscription_events` insert) → notification emit, all
 * committing or rolling back together (design D3).
 *
 * Ordering rationale: the status `UPDATE` runs FIRST (it doubles as the
 * existence/orphan check), then the event insert is the idempotency gate. On a
 * duplicate the whole transaction rolls back, so the status is NOT re-applied
 * and the notification never fires — exactly-once for the user-visible effects.
 *
 * PAID path only. `GRANT` (referral) + unknown event types are benign no-ops
 * that record nothing; the 72h privacy-flip coupling and the time-based
 * grace-elapse downgrade are deferred to their owning worker changes (the
 * `EXPIRATION` handler downgrades to `free` but schedules no privacy flip).
 */
class SubscriptionService(
    private val dataSource: DataSource,
    private val repository: SubscriptionEventRepository,
    private val notifications: NotificationEmitter,
    private val dispatcher: NotificationDispatcher,
    private val clock: () -> Instant = Instant::now,
    // Pool-bounded JDBC dispatcher (docs/11 §3.2); production passes DbDispatchers.db.
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Domain event built by the route after auth + envelope validation. */
    data class IncomingEvent(
        /** Raw RevenueCat wire type (e.g. `INITIAL_PURCHASE`). */
        val type: String,
        val revenuecatEventId: String,
        val userId: UUID,
        val entitlementStart: Instant?,
        val entitlementEnd: Instant?,
        val amountRupiah: Long?,
        val platform: String?,
    )

    /** All variants map to HTTP 200 — see [RevenueCatWebhookRoutes]. */
    sealed interface Result {
        data object Ok : Result

        data object Duplicate : Result

        /** `app_user_id` matched no user — acknowledged so RevenueCat stops retrying. */
        data object UnknownUser : Result

        /** `GRANT` (deferred) or an unknown event type — recorded nothing. */
        data object Ignored : Result
    }

    suspend fun process(event: IncomingEvent): Result {
        val transition =
            when (event.type.uppercase()) {
                "INITIAL_PURCHASE" -> Transition("initial_purchase", STATUS_PREMIUM_ACTIVE, null, EMPTY_BODY)
                "RENEWAL" -> Transition("renewal", STATUS_PREMIUM_ACTIVE, null, EMPTY_BODY)
                "BILLING_ISSUE" ->
                    Transition(
                        "billing_issue",
                        STATUS_PREMIUM_BILLING_RETRY,
                        NotificationType.SUBSCRIPTION_BILLING_ISSUE,
                        billingIssueBody(),
                    )
                // Any EXPIRATION is terminal regardless of expiration_reason
                // (docs/01 § Payment Stack: "only EXPIRATION flips to free").
                "EXPIRATION" -> Transition("expiration", STATUS_FREE, NotificationType.SUBSCRIPTION_EXPIRED, EMPTY_BODY)
                // CANCELLATION keeps the user premium until the period ends — no status change.
                "CANCELLATION" -> Transition("cancellation", null, null, EMPTY_BODY)
                "GRANT" -> {
                    // Deferred to the referral-system change (granted_entitlements /
                    // referral_tickets do not exist yet) — record nothing, grant nothing.
                    log.info("event=revenuecat_grant_deferred user_id={} rc_event_id={}", event.userId, event.revenuecatEventId)
                    return Result.Ignored
                }
                else -> {
                    log.info("event=revenuecat_event_ignored type={} user_id={}", event.type, event.userId)
                    return Result.Ignored
                }
            }

        var emittedId: UUID? = null
        val result =
            withContext(dbDispatcher) {
                dataSource.connection.use { conn ->
                    conn.autoCommit = false
                    try {
                        val userExists = repository.applyStatusReturningExists(conn, event.userId, transition.newStatus)
                        if (!userExists) {
                            conn.rollback()
                            Result.UnknownUser
                        } else {
                            val isNew =
                                repository.insertEventIfNew(
                                    conn = conn,
                                    userId = event.userId,
                                    eventType = transition.eventType,
                                    source = SOURCE_PAID,
                                    revenuecatEventId = event.revenuecatEventId,
                                    entitlementStart = event.entitlementStart,
                                    entitlementEnd = event.entitlementEnd,
                                    amountRupiah = event.amountRupiah,
                                    platform = event.platform,
                                )
                            if (!isNew) {
                                // Duplicate: rollback undoes the status apply (so it is NOT
                                // re-applied) and the notification below never runs.
                                conn.rollback()
                                Result.Duplicate
                            } else {
                                transition.notificationType?.let { type ->
                                    emittedId =
                                        notifications.emit(
                                            conn = conn,
                                            recipientId = event.userId,
                                            actorUserId = null,
                                            type = type,
                                            targetType = null,
                                            targetId = null,
                                            bodyData = transition.notificationBody,
                                        )
                                }
                                conn.commit()
                                Result.Ok
                            }
                        }
                    } catch (t: Throwable) {
                        conn.rollback()
                        throw t
                    } finally {
                        conn.autoCommit = true
                    }
                }
            }

        when (result) {
            Result.Ok ->
                log.info(
                    "event=revenuecat_event_processed type={} user_id={} rc_event_id={}",
                    transition.eventType,
                    event.userId,
                    event.revenuecatEventId,
                )
            Result.Duplicate ->
                log.info("event=revenuecat_event_duplicate user_id={} rc_event_id={}", event.userId, event.revenuecatEventId)
            Result.UnknownUser ->
                log.warn("event=revenuecat_orphan_event user_id={} rc_event_id={}", event.userId, event.revenuecatEventId)
            Result.Ignored -> Unit
        }

        // Post-commit FCM dispatch (fire-and-forget inside the dispatcher impl) so a
        // rolled-back notification is never pushed — mirrors LikeService.
        emittedId?.let(dispatcher::dispatch)
        return result
    }

    private fun billingIssueBody(): JsonObject =
        buildJsonObject {
            put("grace_end_at", JsonPrimitive(clock().plus(Duration.ofDays(GRACE_DAYS)).toString()))
        }

    private data class Transition(
        val eventType: String,
        val newStatus: String?,
        val notificationType: NotificationType?,
        val notificationBody: JsonObject,
    )

    private companion object {
        const val STATUS_PREMIUM_ACTIVE = "premium_active"
        const val STATUS_PREMIUM_BILLING_RETRY = "premium_billing_retry"
        const val STATUS_FREE = "free"
        const val SOURCE_PAID = "paid"
        const val GRACE_DAYS = 7L
        val EMPTY_BODY: JsonObject = buildJsonObject {}
        val log = LoggerFactory.getLogger(SubscriptionService::class.java)
    }
}
