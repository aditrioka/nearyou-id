package id.nearyou.app.image

import id.nearyou.app.config.RemoteConfig
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import id.nearyou.app.infra.cloudflareimages.ImageStore
import id.nearyou.app.infra.cloudflareimages.StoredImage
import id.nearyou.app.infra.cloudvision.ImageModerator
import id.nearyou.app.infra.cloudvision.SafeSearchLikelihood
import id.nearyou.app.infra.cloudvision.SafeSearchLikelihood.UNLIKELY
import id.nearyou.app.infra.cloudvision.SafeSearchLikelihood.VERY_LIKELY
import id.nearyou.app.infra.cloudvision.SafeSearchVerdict
import id.nearyou.app.infra.redis.RedisStringCache
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import java.sql.Connection
import java.time.Duration
import java.util.UUID

private val USER = UUID.randomUUID()
private val CLEAN = SafeSearchVerdict(UNLIKELY, UNLIKELY, UNLIKELY)

private object MissCache : RedisStringCache {
    override fun get(key: String): String? = null

    override fun set(key: String, value: String, ttl: Duration) = Unit
}

private class FakeRemoteConfig(val bool: Boolean? = null, val long: Long? = null) : RemoteConfig {
    override fun getLong(key: String): Long? = long

    override fun getBoolean(key: String): Boolean? = bool
}

private class FakeRateLimiter(
    val throttleAllowed: Boolean = true,
    val dailyAllowed: Boolean = true,
) : RateLimiter {
    override fun tryAcquire(userId: UUID, key: String, capacity: Int, ttl: Duration): RateLimiter.Outcome =
        when {
            key.contains("throttle") -> if (throttleAllowed) RateLimiter.Outcome.Allowed(0) else RateLimiter.Outcome.RateLimited(60)
            key.contains("_day}") -> if (dailyAllowed) RateLimiter.Outcome.Allowed(capacity - 1) else RateLimiter.Outcome.RateLimited(3600)
            else -> RateLimiter.Outcome.Allowed(0)
        }

    override fun tryAcquireByKey(key: String, capacity: Int, ttl: Duration) = RateLimiter.Outcome.Allowed(0)

    override fun releaseMostRecent(userId: UUID, key: String) = Unit
}

private class FakeModerator(
    val configured: Boolean = true,
    val verdict: SafeSearchVerdict = CLEAN,
    val throws: Boolean = false,
) : ImageModerator {
    override fun isConfigured(): Boolean = configured

    override suspend fun safeSearch(bytes: ByteArray): SafeSearchVerdict =
        if (throws) throw RuntimeException("vision boom") else verdict
}

private class FakeStore(val configured: Boolean = true, val throws: Boolean = false) : ImageStore {
    override fun isConfigured(): Boolean = configured

    override suspend fun upload(bytes: ByteArray, contentType: String, fileName: String): StoredImage =
        if (throws) throw RuntimeException("cf boom") else StoredImage("img-1", "https://img-staging.nearyou.id/hash/img-1/public")
}

private class FakeRepo : ImageUploadRepository {
    val inserted = mutableListOf<String>()

    override fun insertUploaded(cfImageId: String, uploaderUserId: UUID, verdict: SafeSearchVerdict) {
        inserted += cfImageId
    }

    override fun findForAttach(conn: Connection, cfImageId: String): ImageUploadRow? = null

    override fun markAttached(conn: Connection, cfImageId: String): Boolean = false
}

private fun service(
    flagBool: Boolean? = true,
    capLong: Long? = null,
    throttleAllowed: Boolean = true,
    dailyAllowed: Boolean = true,
    moderator: ImageModerator = FakeModerator(),
    store: ImageStore = FakeStore(),
    repo: ImageUploadRepository = FakeRepo(),
): ImageUploadService =
    ImageUploadService(
        repository = repo,
        flagGate = ImageUploadFlagGate(MissCache, FakeRemoteConfig(bool = flagBool), dbDispatcher = Dispatchers.Unconfined),
        rateLimiter = ImageUploadRateLimiter(FakeRateLimiter(throttleAllowed, dailyAllowed)),
        moderator = moderator,
        store = store,
        remoteConfig = FakeRemoteConfig(long = capLong),
        dbDispatcher = Dispatchers.Unconfined,
    )

class ImageUploadServiceTest : StringSpec({

    // --- checkGate (flag → premium → throttle → quota) ---

    "flag OFF → FlagDisabled" {
        service(flagBool = false).checkGate(USER, "premium_active") shouldBe ImageUploadService.GateResult.FlagDisabled
    }

    "flag unset (null) → FlagDisabled (fail-closed)" {
        service(flagBool = null).checkGate(USER, "premium_active") shouldBe ImageUploadService.GateResult.FlagDisabled
    }

    "Free tier → PremiumRequired" {
        service().checkGate(USER, "free") shouldBe ImageUploadService.GateResult.PremiumRequired
    }

    "grace-period (premium_billing_retry) → Ok (treated as Premium)" {
        service().checkGate(USER, "premium_billing_retry") shouldBe ImageUploadService.GateResult.Ok
    }

    "premium_active within limits → Ok" {
        service().checkGate(USER, "premium_active") shouldBe ImageUploadService.GateResult.Ok
    }

    "throttle exhausted → Throttled" {
        service(throttleAllowed = false).checkGate(USER, "premium_active")
            .shouldBeInstanceOf<ImageUploadService.GateResult.Throttled>()
    }

    "daily quota exhausted → QuotaExceeded" {
        service(dailyAllowed = false).checkGate(USER, "premium_active")
            .shouldBeInstanceOf<ImageUploadService.GateResult.QuotaExceeded>()
    }

    // --- moderateAndStore (Safe Search → store → ledger) ---

    "Vision unconfigured → Unavailable (no store)" {
        val repo = FakeRepo()
        service(moderator = FakeModerator(configured = false), repo = repo)
            .moderateAndStore(USER, byteArrayOf(1), "image/jpeg", "x.jpg") shouldBe ImageUploadService.StoreResult.Unavailable
        repo.inserted.shouldBeEmpty()
    }

    "Cloudflare unconfigured → Unavailable" {
        service(store = FakeStore(configured = false))
            .moderateAndStore(USER, byteArrayOf(1), "image/jpeg", "x.jpg") shouldBe ImageUploadService.StoreResult.Unavailable
    }

    "adult VERY_LIKELY → Rejected, not stored" {
        val repo = FakeRepo()
        val verdict = SafeSearchVerdict(adult = VERY_LIKELY, violence = UNLIKELY, racy = UNLIKELY)
        service(moderator = FakeModerator(verdict = verdict), repo = repo)
            .moderateAndStore(USER, byteArrayOf(1), "image/jpeg", "x.jpg") shouldBe ImageUploadService.StoreResult.Rejected
        repo.inserted.shouldBeEmpty()
    }

    "racy VERY_LIKELY (adult/violence low) → Rejected (racy is in the reject set)" {
        val verdict = SafeSearchVerdict(adult = UNLIKELY, violence = UNLIKELY, racy = VERY_LIKELY)
        service(moderator = FakeModerator(verdict = verdict))
            .moderateAndStore(USER, byteArrayOf(1), "image/jpeg", "x.jpg") shouldBe ImageUploadService.StoreResult.Rejected
    }

    "adult POSSIBLE → passes (below the LIKELY threshold) and stores" {
        val verdict = SafeSearchVerdict(adult = SafeSearchLikelihood.POSSIBLE, violence = UNLIKELY, racy = SafeSearchLikelihood.POSSIBLE)
        service(moderator = FakeModerator(verdict = verdict))
            .moderateAndStore(USER, byteArrayOf(1), "image/jpeg", "x.jpg")
            .shouldBeInstanceOf<ImageUploadService.StoreResult.Success>()
    }

    "clean image → Success with img-subdomain URL and a ledger row" {
        val repo = FakeRepo()
        val result = service(repo = repo).moderateAndStore(USER, byteArrayOf(1, 2), "image/jpeg", "x.jpg")
        val success = result.shouldBeInstanceOf<ImageUploadService.StoreResult.Success>()
        success.imageId shouldBe "img-1"
        success.deliveryUrl shouldBe "https://img-staging.nearyou.id/hash/img-1/public"
        repo.inserted.shouldContainExactly("img-1")
    }

    "Vision error → Unavailable (fail-closed, not stored)" {
        val repo = FakeRepo()
        service(moderator = FakeModerator(throws = true), repo = repo)
            .moderateAndStore(USER, byteArrayOf(1), "image/jpeg", "x.jpg") shouldBe ImageUploadService.StoreResult.Unavailable
        repo.inserted.shouldBeEmpty()
    }

    "Cloudflare upload error → Unavailable" {
        service(store = FakeStore(throws = true))
            .moderateAndStore(USER, byteArrayOf(1), "image/jpeg", "x.jpg") shouldBe ImageUploadService.StoreResult.Unavailable
    }
})
