package id.nearyou.app.image

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/** Outcome of one per-row orphan sweep ([OrphanImageCleanupRepository.sweepOrphan]). */
enum class OrphanSweepOutcome {
    /** Row + Cloudflare image deleted, transaction committed. */
    DELETED,

    /** The conditional DELETE affected zero rows — a concurrent attach won; Cloudflare untouched. */
    RACED,

    /** The store delete threw — transaction rolled back, row survives for the next run. */
    STORE_FAILED,
}

/**
 * JDBC access for the `orphan-image-cleanup` sweep over the `image_uploads`
 * ledger (V26). `image_uploads` is a ledger, not shadow-ban / block-protected
 * user content, so raw reads need no `visible_*` view or block-join (the
 * [ImageUploadRepository] precedent).
 *
 * JDBC runs on the pool-bounded DB dispatcher (`docs/11` §3.2); production
 * passes `DbDispatchers.db`.
 */
interface OrphanImageCleanupRepository {
    /**
     * The bounded orphan scan (design D4): `status = 'uploaded'` rows older
     * than 24 hours, oldest first, at most 500 per run. The interval literal
     * lives in the query text, not an index predicate — this change adds no
     * index (the table is bounded by the 50/day/user upload quota).
     */
    suspend fun findOrphans(): List<String>

    /**
     * One per-row transaction (design D2): conditional
     * `DELETE … WHERE cf_image_id = ? AND status = 'uploaded'` (uncommitted),
     * then — only when exactly one row was affected — [deleteFromStore], then
     * commit. Zero rows affected → a concurrent attach won since the scan; the
     * store is NOT called and the no-op commits ([OrphanSweepOutcome.RACED]).
     * A [deleteFromStore] throw rolls back so the row survives for the next
     * run ([OrphanSweepOutcome.STORE_FAILED]).
     *
     * The uncommitted DELETE holds the row lock across the store call, so the
     * attach path's own conditional `UPDATE … WHERE status = 'uploaded'`
     * serializes against it — in no interleaving does a post end up attached
     * to a store-deleted image. Holding a lock over HTTP is deliberate and
     * bounded: the row is a ≥24h-old orphan, contention is effectively zero
     * (design Risks/Trade-offs).
     */
    suspend fun sweepOrphan(
        cfImageId: String,
        deleteFromStore: suspend () -> Unit,
    ): OrphanSweepOutcome
}

/** Production JDBC binding for [OrphanImageCleanupRepository]. */
class JdbcOrphanImageCleanupRepository(
    private val dataSource: DataSource,
    // Pool-bounded JDBC dispatcher (docs/11 §3.2); production passes DbDispatchers.db.
    private val dbDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OrphanImageCleanupRepository {
    private val log = LoggerFactory.getLogger(JdbcOrphanImageCleanupRepository::class.java)

    override suspend fun findOrphans(): List<String> =
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                conn.prepareStatement(SELECT_ORPHANS_SQL).use { ps ->
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) add(rs.getString("cf_image_id"))
                        }
                    }
                }
            }
        }

    override suspend fun sweepOrphan(
        cfImageId: String,
        deleteFromStore: suspend () -> Unit,
    ): OrphanSweepOutcome =
        // The whole connection scope runs on the pool-bounded dispatcher (docs/11
        // §3.2) — acquire, statements, rollback/restore, close. The suspend
        // deleteFromStore() HTTP call releases the dispatcher thread at its
        // suspension point, so no DB thread is held during the vendor call.
        withContext(dbDispatcher) {
            dataSource.connection.use { conn ->
                val previousAutoCommit = conn.autoCommit
                conn.autoCommit = false
                try {
                    val rows =
                        conn.prepareStatement(DELETE_ORPHAN_SQL).use { ps ->
                            ps.setString(1, cfImageId)
                            ps.executeUpdate()
                        }
                    if (rows == 0) {
                        conn.commit()
                        return@use OrphanSweepOutcome.RACED
                    }
                    try {
                        deleteFromStore()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        conn.rollback()
                        log.warn(
                            "event=orphan_image_store_delete_failed cf_image_id={} error_class={}",
                            cfImageId,
                            e::class.simpleName,
                        )
                        return@use OrphanSweepOutcome.STORE_FAILED
                    }
                    conn.commit()
                    OrphanSweepOutcome.DELETED
                } catch (e: CancellationException) {
                    runCatching { conn.rollback() }
                    throw e
                } catch (e: Exception) {
                    runCatching { conn.rollback() }
                    throw e
                } finally {
                    runCatching { conn.autoCommit = previousAutoCommit }
                }
            }
        }

    companion object {
        // Bounded threshold scan (design D4): aged unattached uploads, oldest first,
        // LIMIT 500 per run. Backlog beyond the cap drains across daily runs and is
        // visible via the run's scanned count (no silent truncation).
        const val SELECT_ORPHANS_SQL: String =
            """
            SELECT cf_image_id FROM image_uploads
             WHERE status = 'uploaded'
               AND created_at < NOW() - INTERVAL '24 hours'
             ORDER BY created_at
             LIMIT 500
            """

        // Conditional on status so a concurrent attach (its own conditional UPDATE
        // ... WHERE status = 'uploaded') always resolves to exactly one winner.
        const val DELETE_ORPHAN_SQL: String =
            "DELETE FROM image_uploads WHERE cf_image_id = ? AND status = 'uploaded'"
    }
}
