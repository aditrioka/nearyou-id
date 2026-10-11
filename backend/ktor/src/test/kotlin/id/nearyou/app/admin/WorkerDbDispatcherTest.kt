package id.nearyou.app.admin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.sql.SQLException
import java.util.concurrent.Executors
import javax.sql.DataSource

/**
 * docs/11 §3.2: both single-statement workers run their JDBC work on the injected pool-bounded
 * dispatcher (production passes `DbDispatchers.db`), never raw `Dispatchers.IO`. The DataSource
 * records the thread that acquires the connection, then fails — no database needed.
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
})
