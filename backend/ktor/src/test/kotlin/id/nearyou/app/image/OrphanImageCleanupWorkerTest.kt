package id.nearyou.app.image

import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.cleanup
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.hikari
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.seedUser
import id.nearyou.app.admin.retention.RetentionCleanupTestSupport.tag
import id.nearyou.app.image.OrphanImageCleanupTestSupport.imageUploadExists
import id.nearyou.app.image.OrphanImageCleanupTestSupport.seedImageUpload
import id.nearyou.app.image.OrphanImageCleanupTestSupport.statusOf
import id.nearyou.app.infra.cloudflareimages.NoOpImageStore
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * DB-tagged worker tests for the `orphan-image-cleanup` sweep — the scan
 * window (aged-uploaded only), the per-row transactional delete ordering
 * (design D2), CF-failure rollback, the concurrent-attach race guard, the
 * unconfigured fail-soft no-op, and idempotency. Real Postgres
 * (`@Tags("database")`), mirroring the retention-cleanup suite. Determinism
 * in the shared dev DB: each counting test first runs a worker to drain ALL
 * currently-eligible rows, then seeds exactly its own.
 */
@Tags("database")
class OrphanImageCleanupWorkerTest : StringSpec({

    val dataSource = autoClose(hikari())
    val repository = JdbcOrphanImageCleanupRepository(dataSource)

    fun drain() {
        // Drain every currently-eligible orphan (unknown count) so subsequent
        // counts are exact. The recording store no-ops the vendor side.
        kotlinx.coroutines.runBlocking {
            OrphanImageCleanupWorker(repository, RecordingImageStore()).execute()
        }
    }

    "aged uploaded orphan is swept: ledger row deleted AND store delete called" {
        drain()
        val t = tag()
        val u = seedUser(dataSource, t)
        val orphan = seedImageUpload(dataSource, u, createdAt = Instant.now().minus(25, ChronoUnit.HOURS))
        try {
            val store = RecordingImageStore()
            val result = OrphanImageCleanupWorker(repository, store).execute()
            result.scanned shouldBe 1
            result.deleted shouldBe 1
            result.cfFailures shouldBe 0
            store.deleted shouldContain orphan
            imageUploadExists(dataSource, orphan) shouldBe false
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "fresh (<24h) upload and attached row are never selected" {
        drain()
        val t = tag()
        val u = seedUser(dataSource, t)
        val fresh = seedImageUpload(dataSource, u, createdAt = Instant.now().minus(1, ChronoUnit.HOURS))
        val attached =
            seedImageUpload(
                dataSource,
                u,
                createdAt = Instant.now().minus(25, ChronoUnit.HOURS),
                status = "attached",
            )
        try {
            val store = RecordingImageStore()
            val result = OrphanImageCleanupWorker(repository, store).execute()
            result.scanned shouldBe 0
            result.deleted shouldBe 0
            store.deleted shouldNotContain fresh
            store.deleted shouldNotContain attached
            imageUploadExists(dataSource, fresh) shouldBe true
            imageUploadExists(dataSource, attached) shouldBe true
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "store failure rolls back: ledger row survives for the next run, cf_failures counted, run continues" {
        drain()
        val t = tag()
        val u = seedUser(dataSource, t)
        val failing = seedImageUpload(dataSource, u, createdAt = Instant.now().minus(26, ChronoUnit.HOURS))
        val healthy = seedImageUpload(dataSource, u, createdAt = Instant.now().minus(25, ChronoUnit.HOURS))
        try {
            val store = RecordingImageStore(failFor = setOf(failing))
            val result = OrphanImageCleanupWorker(repository, store).execute()
            result.scanned shouldBe 2
            result.deleted shouldBe 1
            result.cfFailures shouldBe 1
            imageUploadExists(dataSource, failing) shouldBe true
            imageUploadExists(dataSource, healthy) shouldBe false
            // The retry converges: the next run (store healthy again) reclaims it.
            val retry = OrphanImageCleanupWorker(repository, RecordingImageStore()).execute()
            retry.deleted shouldBe 1
            imageUploadExists(dataSource, failing) shouldBe false
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "concurrent attach wins: a row flipped after the scan is skipped and the store is never called" {
        val t = tag()
        val u = seedUser(dataSource, t)
        val raced = seedImageUpload(dataSource, u, createdAt = Instant.now().minus(25, ChronoUnit.HOURS))
        try {
            // Simulate the race window directly against sweepOrphan: the attach
            // flip lands between the scan and the worker's conditional DELETE.
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "UPDATE image_uploads SET status = 'attached' WHERE cf_image_id = ? AND status = 'uploaded'",
                ).use { ps ->
                    ps.setString(1, raced)
                    ps.executeUpdate() shouldBe 1
                }
            }
            var storeCalled = false
            val outcome = repository.sweepOrphan(raced) { storeCalled = true }
            outcome shouldBe OrphanSweepOutcome.RACED
            storeCalled shouldBe false
            statusOf(dataSource, raced) shouldBe "attached"
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "unconfigured store fail-softs to an all-zero no-op run" {
        drain()
        val t = tag()
        val u = seedUser(dataSource, t)
        val orphan = seedImageUpload(dataSource, u, createdAt = Instant.now().minus(25, ChronoUnit.HOURS))
        try {
            val result = OrphanImageCleanupWorker(repository, NoOpImageStore).execute()
            result.scanned shouldBe 0
            result.deleted shouldBe 0
            result.cfFailures shouldBe 0
            imageUploadExists(dataSource, orphan) shouldBe true
        } finally {
            cleanup(dataSource, listOf(u))
        }
    }

    "idempotent re-run with no newly-aged orphans reports all zeros" {
        drain()
        val result = OrphanImageCleanupWorker(repository, RecordingImageStore()).execute()
        result.scanned shouldBe 0
        result.deleted shouldBe 0
        result.cfFailures shouldBe 0
    }
})
