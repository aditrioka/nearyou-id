package id.nearyou.app.account

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/*
 * Deterministic lock-race helpers for the deletion concurrency specs: a test holds a row lock
 * in a session OUTSIDE the pool, starts the code under test in the background, and waits on
 * pg_locks until that code is provably blocked — no sleeps-and-hope.
 */

/** A manual-commit session outside [this] pool (its peak stays at the pool size + 1). */
internal fun HikariDataSource.otherSession(): Connection =
    DriverManager.getConnection(jdbcUrl, username, password).apply { autoCommit = false }

// Own threads, not the common pool: a blocked JDBC call must not starve the next background task.
private val raceThreads = Executors.newCachedThreadPool { r -> Thread(r, "lock-race").apply { isDaemon = true } }

internal fun <T> inBackground(block: suspend () -> T): CompletableFuture<T> =
    CompletableFuture.supplyAsync({ runBlocking { block() } }, raceThreads)

internal fun Connection.backendPid(): Int =
    createStatement().use { st ->
        st.executeQuery("SELECT pg_backend_pid()").use { rs ->
            rs.next()
            rs.getInt(1)
        }
    }

/**
 * Waits (≤10 s) until some backend is blocked on a lock held by [blockerPid]; returns its pid.
 * `pg_locks` / `pg_blocking_pids` read the live lock table, so polling from inside an open
 * transaction is safe (unlike the per-transaction `pg_stat_activity` snapshot).
 */
internal fun Connection.awaitWaiterOn(blockerPid: Int): Int {
    repeat(100) {
        prepareStatement("SELECT pid FROM pg_locks WHERE NOT granted AND ? = ANY(pg_blocking_pids(pid)) LIMIT 1").use { ps ->
            ps.setInt(1, blockerPid)
            ps.executeQuery().use { rs -> if (rs.next()) return rs.getInt(1) }
        }
        Thread.sleep(100)
    }
    error("no backend blocked on pid $blockerPid within 10s")
}
