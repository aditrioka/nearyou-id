package id.nearyou.app.infra.r2

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * Fail-soft [ObjectStore] bound when R2 is unconfigured (dev/test/un-provisioned staging) —
 * the `NoOpImageModerator` / `NoOpImageStore` precedent. Application boot does not fail; the
 * consuming worker degrades gracefully.
 *
 * - [put] / [delete] are no-ops (a single WARN the first time each instance is hit, so an
 *   unconfigured environment is visible in logs without flooding them).
 * - [presignedGetUrl] throws the defined [ObjectStoreUnconfiguredException] — there is no
 *   URL to produce; the worker catches it and maps the request to a soft `failed`, never a
 *   crash. (A defined, catchable type, distinct from the no-op writes, so the consumer can
 *   tell "storage unconfigured" apart from a real failure.)
 */
object NoOpObjectStore : ObjectStore {
    private val log = LoggerFactory.getLogger(NoOpObjectStore::class.java)
    private val warnedPut = AtomicBoolean(false)
    private val warnedDelete = AtomicBoolean(false)

    override fun isConfigured(): Boolean = false

    override suspend fun put(
        key: String,
        bytes: ByteArray,
        contentType: String,
    ) {
        if (warnedPut.compareAndSet(false, true)) {
            log.warn("event=r2_put status=noop_unconfigured (R2 credentials unset — gate on isConfigured())")
        }
    }

    override suspend fun presignedGetUrl(
        key: String,
        ttl: Duration,
    ): String = throw ObjectStoreUnconfiguredException()

    override suspend fun delete(key: String) {
        if (warnedDelete.compareAndSet(false, true)) {
            log.warn("event=r2_delete status=noop_unconfigured (R2 credentials unset — gate on isConfigured())")
        }
    }
}
