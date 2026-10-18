package id.nearyou.app.admin

import id.nearyou.app.core.domain.health.ProbeError
import id.nearyou.app.health.JdbcPostgresProbe
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.sql.DataSource

/**
 * docs/11 §3.2: the single-statement workers and the `/health/ready` Postgres probe run their JDBC
 * work on the injected pool-bounded dispatcher (production passes `DbDispatchers.db`), never raw
 * `Dispatchers.IO`. The DataSource records the thread that acquires the connection, then fails —
 * no database needed.
 */
class WorkerDbDispatcherTest : StringSpec({

    val threadName = "db-dispatcher-probe"
    val executor = Executors.newSingleThreadExecutor { Thread(it, threadName) }
    val dispatcher = executor.asCoroutineDispatcher()
    afterSpec { executor.shutdownNow() }

    fun recordingDataSource(seen: MutableList<String>): DataSource =
        Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, method, _ ->
            if (method.name == "getConnection") {
                seen += Thread.currentThread().name
                throw SQLException("probe: no database")
            }
            error("unexpected DataSource.${method.name}")
        } as DataSource

    "SuspensionUnbanWorker acquires its connection on the injected dbDispatcher" {
        val seen = mutableListOf<String>()
        shouldThrow<SQLException> { runBlocking { SuspensionUnbanWorker(recordingDataSource(seen), dispatcher).execute() } }
        seen shouldHaveSize 1
        seen.single() shouldStartWith threadName // debug mode suffixes " @coroutine#N"
    }

    "PrivacyFlipWorker acquires its connection on the injected dbDispatcher" {
        val seen = mutableListOf<String>()
        shouldThrow<SQLException> { runBlocking { PrivacyFlipWorker(recordingDataSource(seen), dispatcher).execute() } }
        seen shouldHaveSize 1
        seen.single() shouldStartWith threadName // debug mode suffixes " @coroutine#N"
    }

    "JdbcPostgresProbe acquires its connection on the injected dbDispatcher" {
        val seen = mutableListOf<String>()
        val result = runBlocking { JdbcPostgresProbe(recordingDataSource(seen), dispatcher).probe(Duration.ofSeconds(5)) }
        result.error shouldBe ProbeError.CONNECTION_REFUSED
        seen shouldHaveSize 1
        seen.single() shouldStartWith threadName // debug mode suffixes " @coroutine#N"
    }

    "JdbcPostgresProbe queued behind a saturated dbDispatcher times out and never borrows a connection" {
        // Every dispatcher slot is busy (pool saturated). The probe queues for a slot instead of
        // blocking an extra IO thread on the pool borrow; its budget lapses while queued, so when
        // the slot frees the cancelled probe is discarded WITHOUT taking a connection from real work.
        val seen = mutableListOf<String>()
        val release = CountDownLatch(1)
        executor.submit { release.await() }
        try {
            val result =
                runBlocking {
                    val probe = async { JdbcPostgresProbe(recordingDataSource(seen), dispatcher).probe(Duration.ofMillis(200)) }
                    delay(600) // the 200 ms budget lapses while the probe is still queued
                    release.countDown()
                    probe.await()
                }
            result.error shouldBe ProbeError.TIMEOUT
            seen.shouldBeEmpty()
        } finally {
            release.countDown()
        }
    }
})
