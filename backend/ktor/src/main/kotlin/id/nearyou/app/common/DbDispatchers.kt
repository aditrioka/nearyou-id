package id.nearyou.app.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The single bounded dispatcher for ALL blocking JDBC work
 * (`docs/11-Engineering-Standards.md` §3.2 rule #1).
 *
 * Sized to the Hikari pool: with parallelism == maxPoolSize, a request flood
 * queues as suspended coroutines (cheap) instead of stacking `Dispatchers.IO`'s
 * 64 threads against a pool that can only serve `maxPoolSize` of them (thread
 * starvation + `connectionTimeout` amplification on unrelated requests).
 *
 * Inject via Koin (`single { dbDispatchers }`); JDBC call sites hop with
 * `withContext(dbDispatchers.db) { ... }`. Never use raw `Dispatchers.IO`
 * for JDBC.
 */
class DbDispatchers(poolSize: Int) {
    val db: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(poolSize)
}
