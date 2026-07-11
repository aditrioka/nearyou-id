package id.nearyou.app.image

import id.nearyou.app.infra.cloudflareimages.ImageStore
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("id.nearyou.app.image.OrphanImageCleanupWorker")

/**
 * Result of a single [OrphanImageCleanupWorker.execute] run — the counts the
 * route ([orphanImageCleanupRoutes]) returns in the response body.
 */
data class OrphanImageCleanupResult(
    val scanned: Int,
    val deleted: Int,
    val cfFailures: Int,
)

/**
 * Daily worker (Cloud-Scheduler-invoked via `POST /internal/cleanup-orphan-images`)
 * for the `orphan-image-cleanup` capability: reclaims uploaded-but-never-attached
 * images — `image_uploads` rows still `status = 'uploaded'` after the 24-hour
 * grace window — deleting the Cloudflare image and the ledger row per-row
 * transactionally ([OrphanImageCleanupRepository.sweepOrphan], design D2).
 *
 * Fail-soft when unconfigured: without Cloudflare credentials
 * (`ImageStore.isConfigured()` false) the run performs no scan and no deletes
 * and returns all-zero counts — an unconfigured environment must never error
 * the scheduler invocation, and must never delete ledger rows whose Cloudflare
 * objects it cannot delete.
 *
 * One structured INFO line per run (`event=orphan_image_cleanup` with counts +
 * `duration_ms`) and nothing else — no per-row logging, no `admin_actions_log`
 * writes (routine hygiene; the [id.nearyou.app.admin.retention.RetentionCleanupWorker]
 * discipline). Idempotent: a re-run with no newly-aged orphans reports all zeros.
 * A single row's Cloudflare failure rolls back that row only and the run continues.
 */
class OrphanImageCleanupWorker(
    private val repository: OrphanImageCleanupRepository,
    private val imageStore: ImageStore,
) {
    suspend fun execute(): OrphanImageCleanupResult {
        if (!imageStore.isConfigured()) {
            logger.info("event=orphan_image_cleanup scanned=0 deleted=0 cf_failures=0 duration_ms=0 unconfigured=true")
            return OrphanImageCleanupResult(scanned = 0, deleted = 0, cfFailures = 0)
        }

        val startNanos = System.nanoTime()
        val orphans = repository.findOrphans()
        var deleted = 0
        var cfFailures = 0
        for (cfImageId in orphans) {
            when (repository.sweepOrphan(cfImageId) { imageStore.delete(cfImageId) }) {
                OrphanSweepOutcome.DELETED -> deleted++
                OrphanSweepOutcome.RACED -> Unit
                OrphanSweepOutcome.STORE_FAILED -> cfFailures++
            }
        }
        val durationMs = (System.nanoTime() - startNanos) / 1_000_000L

        logger.info(
            "event=orphan_image_cleanup scanned={} deleted={} cf_failures={} duration_ms={}",
            orphans.size,
            deleted,
            cfFailures,
            durationMs,
        )

        return OrphanImageCleanupResult(scanned = orphans.size, deleted = deleted, cfFailures = cfFailures)
    }
}
