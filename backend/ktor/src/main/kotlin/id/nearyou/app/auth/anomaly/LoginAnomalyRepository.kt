package id.nearyou.app.auth.anomaly

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * One user flagged by the login-source-spread detector: their `user_id` and the
 * number of distinct non-NULL `/24` subnets they logged in from within the
 * detection window. [distinctSubnets] is a non-PII aggregate (a count, never a
 * subnet value) — safe to carry into the `moderation_queue.notes` summary.
 */
data class FlaggedUser(
    val userId: UUID,
    val distinctSubnets: Int,
)

/**
 * Data seam for the `auth-login-anomaly-detection` worker — a windowed read over
 * `login_events` plus the idempotent anomaly write to `moderation_queue`. Two
 * self-contained JDBC statements (the change is scoped to `:backend:ktor` only;
 * design Impact / Decision 2 — it deliberately does NOT extend the shared
 * `core/data` [id.nearyou.data.repository.ModerationQueueRepository], whose
 * caller-supplied-`Connection` API does not fit this fail-soft-per-user batch
 * sweep). The fake test impl stubs this seam for the fail-soft scenario.
 */
interface LoginAnomalyRepository {
    /**
     * Return every user whose `COUNT(DISTINCT ip_subnet_24)` (NULLs excluded) is
     * strictly greater than [threshold] across `login_events` rows with
     * `occurred_at > windowStart`. [windowStart] is the single per-sweep
     * evaluation instant minus the detection window, bound as a `PreparedStatement`
     * timestamp parameter (never `NOW()` in the predicate — and never an index
     * predicate; the read is served by the existing
     * `login_events (user_id, occurred_at DESC)` index, V35). [threshold] is bound,
     * not interpolated.
     */
    suspend fun findUsersExceedingSubnetThreshold(
        windowStart: Instant,
        threshold: Int,
    ): List<FlaggedUser>

    /**
     * Record one flagged user as an idempotent `moderation_queue` row
     * (`target_type='user'`, `trigger='anomaly_detection'`, `status='pending'`)
     * via `ON CONFLICT (target_type, target_id, trigger) DO NOTHING`. [notes] is
     * the non-PII summary (count + window — never an IP / subnet / identifier
     * hash). Returns `true` when a NEW row was inserted, `false` when the
     * ON CONFLICT branch fired (the user already has an `anomaly_detection` row —
     * status-agnostic, so even an admin-`resolved` row suppresses the re-enqueue;
     * design Risks / spec "already-resolved anomaly row also suppresses
     * re-enqueue"). Its own auto-commit connection — there is no enclosing
     * transaction, so one user's write neither sees nor blocks another's
     * (fail-soft is the [LoginAnomalyDetectionService]'s concern).
     */
    suspend fun recordAnomaly(
        userId: UUID,
        notes: String,
    ): Boolean
}

/**
 * JDBC [LoginAnomalyRepository]. Both statements run on the pool-bounded DB
 * dispatcher (`docs/11-Engineering-Standards.md` §3.2 — never raw
 * `Dispatchers.IO`; production passes `DbDispatchers.db`), mirroring
 * [id.nearyou.app.auth.loginhistory.JdbcLoginEventRepository] /
 * [id.nearyou.app.admin.retention.JdbcRetentionCleanupRepository].
 *
 * Zero schema change: `login_events` (V35) and `moderation_queue` (the
 * `anomaly_detection` trigger + `user` target_type, valid in the V36-restated
 * CHECK) already exist.
 */
class JdbcLoginAnomalyRepository(
    private val dataSource: DataSource,
    // Pool-bounded JDBC dispatcher (docs/11 §3.2); production passes DbDispatchers.db.
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LoginAnomalyRepository {
    override suspend fun findUsersExceedingSubnetThreshold(
        windowStart: Instant,
        threshold: Int,
    ): List<FlaggedUser> =
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(DETECT_SQL).use { ps ->
                    ps.setTimestamp(1, Timestamp.from(windowStart))
                    ps.setInt(2, threshold)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    FlaggedUser(
                                        userId = rs.getObject("user_id", UUID::class.java),
                                        distinctSubnets = rs.getInt("distinct_subnets"),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

    override suspend fun recordAnomaly(
        userId: UUID,
        notes: String,
    ): Boolean =
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                // NON-PII CONTRACT: `notes` carries only the distinct-subnet count +
                // window label — never an IP, `ip_subnet_24` value, or identifier hash
                // (spec "PII and logging discipline"; the guard is a test, this comment
                // prevents a future-edit regression). The only PII-adjacent value is
                // `target_id` = the non-secret user PK.
                conn.prepareStatement(INSERT_SQL).use { ps ->
                    ps.setObject(1, userId)
                    ps.setString(2, notes)
                    ps.executeUpdate() == 1
                }
            }
        }

    private companion object {
        // Windowed per-user distinct-subnet spread. COUNT(DISTINCT) already ignores
        // NULLs; the explicit `ip_subnet_24 IS NOT NULL` filter makes the NULL
        // exclusion self-documenting and trims grouped rows. `occurred_at > ?` binds
        // the evaluation instant minus the window (no NOW() in the predicate); the
        // `> ?` threshold is bound, not interpolated. Served by login_events_user_occurred_idx.
        const val DETECT_SQL =
            """
            SELECT user_id, COUNT(DISTINCT ip_subnet_24) AS distinct_subnets
              FROM login_events
             WHERE occurred_at > ?
               AND ip_subnet_24 IS NOT NULL
             GROUP BY user_id
            HAVING COUNT(DISTINCT ip_subnet_24) > ?
            """

        // Idempotent durable signal. status='pending' is explicit (matches the spec's
        // recorded-row shape though it equals the schema default). ON CONFLICT DO
        // NOTHING against UNIQUE (target_type, target_id, trigger) → one row per user.
        const val INSERT_SQL =
            """
            INSERT INTO moderation_queue (target_type, target_id, trigger, status, notes)
            VALUES ('user', ?, 'anomaly_detection', 'pending', ?)
            ON CONFLICT (target_type, target_id, trigger) DO NOTHING
            """
    }
}
