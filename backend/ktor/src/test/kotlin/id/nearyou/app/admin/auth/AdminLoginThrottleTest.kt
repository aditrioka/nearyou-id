package id.nearyou.app.admin.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.core.domain.ratelimit.InMemoryRateLimiter
import id.nearyou.app.core.domain.ratelimit.RateLimiter
import id.nearyou.app.infra.otel.IpHasher
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * `admin-login` "Login POST is throttled per client IP and per submitted email"
 * (#532) — one test per spec scenario. Throttle state comes from an injected
 * [InMemoryRateLimiter] (same sliding-window semantics as the Redis Lua script);
 * phantom emails keep each attempt to one sentinel Argon2id verify. DB-backed.
 */
@Tags("database")
class AdminLoginThrottleTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seeded = mutableListOf<UUID>()
    afterEach {
        seeded.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seeded.clear()
    }

    fun seedAdmin(): AdminAuthTestSupport.SeededAdmin = AdminAuthTestSupport.seedAdmin(dataSource).also { seeded += it.id }

    suspend fun HttpClient.login(
        email: String,
        ip: String = PROBE_IP,
        password: String = "wrong-password",
        totp: String = "000000",
    ): HttpResponse =
        post("/admin/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            header("CF-Connecting-IP", ip)
            header(HttpHeaders.UserAgent, PROBE_AGENT)
            setBody(listOf("email" to email, "password" to password, "totp" to totp).formUrlEncode())
        }

    "IP tier: the 11th attempt from one IP within 15 minutes is 429 + Retry-After" {
        AdminAuthTestSupport.withAdminApp(dataSource, loginRateLimiter = InMemoryRateLimiter()) { client ->
            repeat(AdminLoginRoutes.IP_CAP) { i -> client.login(phantom("ip$i")).status shouldBe HttpStatusCode.OK }
            val res = client.login(phantom("ip-over"))
            res.status shouldBe HttpStatusCode.TooManyRequests
            val retryAfter = res.headers[HttpHeaders.RetryAfter]!!.toLong()
            retryAfter shouldBeInRange 1L..900L
            val body = res.bodyAsText()
            // N = Retry-After minutes, rounded up.
            body shouldContain AdminLoginRoutes.throttledMessage((retryAfter + 59) / 60)
            body shouldContain "Too many login attempts. Try again in "
            body shouldContain "action=\"/admin/login\""
        }
    }

    "email burst tier: the 6th attempt for one email within 15 minutes is 429 across IPs" {
        val target = phantom("burst")
        AdminAuthTestSupport.withAdminApp(dataSource, loginRateLimiter = InMemoryRateLimiter()) { client ->
            repeat(AdminLoginRoutes.EMAIL_CAP) { i -> client.login(target, ip = "198.51.100.${i + 1}").status shouldBe HttpStatusCode.OK }
            val res = client.login(target, ip = "198.51.100.99")
            res.status shouldBe HttpStatusCode.TooManyRequests
            res.headers[HttpHeaders.RetryAfter]!!.toLong() shouldBeInRange 1L..900L
        }
    }

    "lockout tier: sustained attempts spaced past the burst window trip the 24-hour lockout" {
        var now = Instant.parse("2026-10-18T00:00:00Z")
        val limiter = InMemoryRateLimiter(clock = { now })
        val target = phantom("lockout")
        val lines =
            captureAuditWarnings {
                AdminAuthTestSupport.withAdminApp(dataSource, loginRateLimiter = limiter) { client ->
                    // 4 batches of 5, 16 min apart: no 15-min window ever holds more than 5.
                    repeat(AdminLoginRoutes.LOCKOUT_CAP / AdminLoginRoutes.EMAIL_CAP) {
                        repeat(AdminLoginRoutes.EMAIL_CAP) { client.login(target).status shouldBe HttpStatusCode.OK }
                        now = now.plus(Duration.ofMinutes(16))
                    }
                    val res = client.login(target)
                    res.status shouldBe HttpStatusCode.TooManyRequests
                    // The lockout tier answered (a burst-tier Retry-After is <= 900 s).
                    res.headers[HttpHeaders.RetryAfter]!!.toLong() shouldBeGreaterThan 900L
                }
            }
        lines.single { it.contains("event=admin_login_throttled") } shouldContain "axis=email_lockout"
    }

    "a throttled attempt with correct credentials does not log in (no cookie, session or audit row)" {
        val admin = seedAdmin()
        val limiter = InMemoryRateLimiter().also { it.exhaust(emailKey(admin.email), AdminLoginRoutes.EMAIL_CAP) }
        AdminAuthTestSupport.withAdminApp(dataSource, loginRateLimiter = limiter) { client ->
            val res =
                client.login(
                    admin.email,
                    password = admin.password,
                    totp = AdminAuthTestSupport.currentTotpCode(admin.totpSecret!!),
                )
            res.status shouldBe HttpStatusCode.TooManyRequests
            res.headers.getAll(HttpHeaders.SetCookie).orEmpty()
                .filter { it.startsWith(AdminAuthProvider.COOKIE_NAME) }
                .shouldBeEmpty()
            AdminAuthTestSupport.countSessions(dataSource, admin.id) shouldBe 0
            AdminAuthTestSupport.countAuditRows(dataSource, admin.id) shouldBe 0
        }
    }

    "the throttled response is identical for an existing and a phantom email" {
        val admin = seedAdmin()
        val phantom = phantom("identical")
        // A frozen clock makes both Retry-After values identical, so the FULL header map
        // (names and values) can be compared, not just the names.
        val frozen = Instant.parse("2026-10-18T00:00:00Z")
        val limiter =
            InMemoryRateLimiter(clock = { frozen }).also {
                it.exhaust(emailKey(admin.email), AdminLoginRoutes.EMAIL_CAP)
                it.exhaust(emailKey(phantom), AdminLoginRoutes.EMAIL_CAP)
            }
        AdminAuthTestSupport.withAdminApp(dataSource, loginRateLimiter = limiter) { client ->
            val existing = client.login(admin.email)
            val missing = client.login(phantom)
            existing.status shouldBe HttpStatusCode.TooManyRequests
            missing.status shouldBe HttpStatusCode.TooManyRequests
            existing.bodyAsText() shouldBe missing.bodyAsText()
            existing.headers.entries().associate { it.key to it.value } shouldBe
                missing.headers.entries().associate { it.key to it.value }
        }
    }

    "a throttled attempt emits the structured WARN line and writes no audit row" {
        val admin = seedAdmin()
        val limiter = InMemoryRateLimiter().also { it.exhaust(emailKey(admin.email), AdminLoginRoutes.EMAIL_CAP) }
        val lines =
            captureAuditWarnings {
                AdminAuthTestSupport.withAdminApp(dataSource, loginRateLimiter = limiter) { client ->
                    client.login(admin.email, password = "P@ssw0rd!Secret", totp = "123456").status shouldBe
                        HttpStatusCode.TooManyRequests
                }
            }
        val line = lines.single { it.contains("event=admin_login_throttled") }
        line shouldContain "axis=email " // not axis=email_lockout
        line shouldContain "email_hash=${AdminAuditLogger.sha256Base64Url(admin.email)}"
        line shouldContain "ip=\"$PROBE_IP\""
        line shouldContain "user_agent=\"$PROBE_AGENT\""
        line shouldContain "retry_after_s="
        line shouldNotContain admin.email
        line shouldNotContain "P@ssw0rd!Secret"
        line shouldNotContain "123456"
        AdminAuthTestSupport.countAuditRows(dataSource, admin.id) shouldBe 0
    }

    "client-controlled log fields are quoted and escaped (no field forgery)" {
        val target = phantom("forge")
        val forgedIp = "$PROBE_IP axis=email \"x"
        val limiter = InMemoryRateLimiter().also { it.exhaust(ipKey(forgedIp), AdminLoginRoutes.IP_CAP) }
        val lines =
            captureAuditWarnings {
                AdminAuthTestSupport.withAdminApp(dataSource, loginRateLimiter = limiter) { client ->
                    client.login(target, ip = forgedIp).status shouldBe HttpStatusCode.TooManyRequests
                }
            }
        val line = lines.single { it.contains("event=admin_login_throttled") }
        line shouldContain "axis=ip "
        line shouldContain "ip=\"$PROBE_IP axis=email \\\"x\""
        // Exactly one unquoted axis field: the forged one stays inside the ip value.
        Regex("""(?<!ip="$PROBE_IP )axis=""").findAll(line).count() shouldBe 1
    }

    "a successful login hands its slot back in every tier (failed attempts only count)" {
        val admin = seedAdmin()
        val limiter = InMemoryRateLimiter()
        AdminAuthTestSupport.withAdminApp(dataSource, loginRateLimiter = limiter) { client ->
            // More successes than the email burst cap allows attempts — none is throttled.
            repeat(AdminLoginRoutes.EMAIL_CAP + 1) {
                client.login(admin.email, password = admin.password, totp = AdminAuthTestSupport.currentTotpCode(admin.totpSecret!!))
                    .status shouldBe HttpStatusCode.SeeOther
            }
        }
        // Every tier is back to empty: a probe sees the full capacity minus its own slot.
        limiter.tryAcquireByKey(ipKey(PROBE_IP), AdminLoginRoutes.IP_CAP, AdminLoginRoutes.BURST_WINDOW) shouldBe
            RateLimiter.Outcome.Allowed(remaining = AdminLoginRoutes.IP_CAP - 1)
        limiter.tryAcquireByKey(emailKey(admin.email), AdminLoginRoutes.EMAIL_CAP, AdminLoginRoutes.BURST_WINDOW) shouldBe
            RateLimiter.Outcome.Allowed(remaining = AdminLoginRoutes.EMAIL_CAP - 1)
        limiter.tryAcquireByKey(lockoutKey(admin.email), AdminLoginRoutes.LOCKOUT_CAP, AdminLoginRoutes.LOCKOUT_WINDOW) shouldBe
            RateLimiter.Outcome.Allowed(remaining = AdminLoginRoutes.LOCKOUT_CAP - 1)
    }

    "a request that cannot get a credential-check permit is shed with 429 before any Redis or DB work" {
        val gate = Semaphore(1).also { it.acquire() } // every permit held by "another" check
        val recorder = RecordingLimiter()
        val lines =
            captureAuditWarnings {
                AdminAuthTestSupport.withAdminApp(dataSource, loginRateLimiter = recorder, loginCredentialGate = gate) { client ->
                    val res = client.login(phantom("shed"))
                    res.status shouldBe HttpStatusCode.TooManyRequests
                    res.headers[HttpHeaders.RetryAfter] shouldBe AdminLoginRoutes.SHED_RETRY_AFTER_SECONDS.toString()
                    res.bodyAsText() shouldContain AdminLoginRoutes.throttledMessage(1)
                }
            }
        recorder.calls.shouldBeEmpty() // shed before the throttle tiers
        lines.single { it.contains("event=admin_login_throttled") } shouldContain "axis=concurrency"
    }

    "the credential-check gate never leaks a permit across successes, failures, throttles and 400s" {
        val admin = seedAdmin()
        val gate = Semaphore(1)
        AdminAuthTestSupport.withAdminApp(dataSource, loginRateLimiter = InMemoryRateLimiter(), loginCredentialGate = gate) { client ->
            client.login(admin.email, password = admin.password, totp = AdminAuthTestSupport.currentTotpCode(admin.totpSecret!!))
                .status shouldBe HttpStatusCode.SeeOther
            client.login(admin.email).status shouldBe HttpStatusCode.OK // wrong password
            client.login(phantom("miss")).status shouldBe HttpStatusCode.OK // email not found
            client.login("a".repeat(AdminLoginRoutes.MAX_FIELD_LENGTH + 1)).status shouldBe HttpStatusCode.BadRequest
        }
        val burst = InMemoryRateLimiter().also { it.exhaust(emailKey(admin.email), AdminLoginRoutes.EMAIL_CAP) }
        AdminAuthTestSupport.withAdminApp(dataSource, loginRateLimiter = burst, loginCredentialGate = gate) { client ->
            client.login(admin.email).status shouldBe HttpStatusCode.TooManyRequests // throttled inside the gate
        }
        gate.availablePermits shouldBe 1
    }

    "throttle keys use the two-segment hash-tag shape, hashed IP, in IP → email → lockout order" {
        val target = phantom("keys")
        val recorder = RecordingLimiter()
        AdminAuthTestSupport.withAdminApp(dataSource, loginRateLimiter = recorder) { client ->
            client.login(target, ip = PROBE_IP).status shouldBe HttpStatusCode.OK
        }
        val emailHash = AdminAuditLogger.sha256Base64Url(target)
        recorder.calls shouldBe
            listOf(
                Triple("{scope:rate_admin_login}:{ip:${IpHasher.hash(PROBE_IP)}}", 10, Duration.ofMinutes(15)),
                Triple("{scope:rate_admin_login}:{email:$emailHash}", 5, Duration.ofMinutes(15)),
                Triple("{scope:rate_admin_login_lockout}:{email:$emailHash}", 20, Duration.ofHours(24)),
            )
        recorder.calls.filter { (key, _, _) -> key.contains(PROBE_IP) || key.contains(target) }.shouldBeEmpty()
    }

    "an IP-throttled attempt consumes no email slot" {
        val target = phantom("ip-first")
        val limiter = InMemoryRateLimiter().also { it.exhaust(ipKey(PROBE_IP), AdminLoginRoutes.IP_CAP) }
        AdminAuthTestSupport.withAdminApp(dataSource, loginRateLimiter = limiter) { client ->
            client.login(target, ip = PROBE_IP).status shouldBe HttpStatusCode.TooManyRequests
            // Another, unthrottled IP is still admitted to the credential checks.
            client.login(target, ip = "198.51.100.42").status shouldBe HttpStatusCode.OK
        }
        // Exactly one email slot used (the 198.51.100.42 attempt), not two.
        val probe = limiter.tryAcquireByKey(emailKey(target), AdminLoginRoutes.EMAIL_CAP, AdminLoginRoutes.BURST_WINDOW)
        probe shouldBe RateLimiter.Outcome.Allowed(remaining = AdminLoginRoutes.EMAIL_CAP - 2)
    }

    "the throttle fails open when the limiter admits (NoOp binding / Redis fail-soft)" {
        val target = phantom("fail-open")
        // withAdminApp's default limiter is NoOpRateLimiter — the same Allowed outcome
        // RedisRateLimiter returns when Redis is unreachable.
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            repeat(12) {
                val res = client.login(target)
                res.status shouldBe HttpStatusCode.OK
                res.bodyAsText() shouldContain AdminLoginRoutes.GENERIC_ERROR_MESSAGE
            }
        }
    }

    "a burst-throttled attempt spends no lockout slot" {
        val target = phantom("burst-only")
        val limiter = InMemoryRateLimiter().also { it.exhaust(emailKey(target), AdminLoginRoutes.EMAIL_CAP) }
        AdminAuthTestSupport.withAdminApp(dataSource, loginRateLimiter = limiter) { client ->
            client.login(target).status shouldBe HttpStatusCode.TooManyRequests
        }
        // A burst spray must never feed the 24 h account lockout: the probe sees it empty.
        limiter.tryAcquireByKey(lockoutKey(target), AdminLoginRoutes.LOCKOUT_CAP, AdminLoginRoutes.LOCKOUT_WINDOW) shouldBe
            RateLimiter.Outcome.Allowed(remaining = AdminLoginRoutes.LOCKOUT_CAP - 1)
    }

    "logQuoted quotes and escapes backslash, quote, CR and LF" {
        AdminAuditLogger.logQuoted("a\\b\r\nc\"d") shouldBe "\"a\\\\b\\r\\nc\\\"d\""
        AdminAuditLogger.logQuoted("") shouldBe "\"\""
    }

    "the credential-check gate returns its permit when a credential check throws" {
        val gate = Semaphore(1)
        val throwing =
            object : RateLimiter {
                override fun tryAcquireByKey(
                    key: String,
                    capacity: Int,
                    ttl: Duration,
                ): RateLimiter.Outcome = error("simulated limiter failure")

                override fun tryAcquire(
                    userId: UUID,
                    key: String,
                    capacity: Int,
                    ttl: Duration,
                ): RateLimiter.Outcome = error("unused")

                override fun releaseMostRecent(
                    userId: UUID,
                    key: String,
                ) = Unit
            }
        AdminAuthTestSupport.withAdminApp(
            dataSource,
            loginRateLimiter = throwing,
            loginCredentialGate = gate,
            // production StatusPages maps the throw to a 500
            productionBodyLimit = true,
        ) { client ->
            client.login(phantom("throws")).status shouldBe HttpStatusCode.InternalServerError
        }
        gate.availablePermits shouldBe 1
    }

    "an oversized field is rejected 400 before the throttle consumes a slot" {
        val recorder = RecordingLimiter()
        AdminAuthTestSupport.withAdminApp(dataSource, loginRateLimiter = recorder) { client ->
            client.login("a".repeat(AdminLoginRoutes.MAX_FIELD_LENGTH + 1)).status shouldBe HttpStatusCode.BadRequest
        }
        recorder.calls.shouldBeEmpty()
    }
}) {
    /** Records every `tryAcquireByKey` (key, capacity, ttl) and always admits. */
    private class RecordingLimiter : RateLimiter {
        val calls = mutableListOf<Triple<String, Int, Duration>>()

        override fun tryAcquireByKey(
            key: String,
            capacity: Int,
            ttl: Duration,
        ): RateLimiter.Outcome {
            calls += Triple(key, capacity, ttl)
            return RateLimiter.Outcome.Allowed(remaining = capacity - 1)
        }

        override fun tryAcquire(
            userId: UUID,
            key: String,
            capacity: Int,
            ttl: Duration,
        ): RateLimiter.Outcome = error("the admin login throttle is not user-keyed")

        override fun releaseMostRecent(
            userId: UUID,
            key: String,
        ) = Unit // a successful login hands its slots back; nothing to record here
    }

    companion object {
        private const val PROBE_IP = "203.0.113.7"
        private const val PROBE_AGENT = "throttle-probe"
        private const val PHANTOM_DOMAIN = "nearyou.id"

        /** A unique address with no `admin_users` row. */
        fun phantom(tag: String): String = "phantom-$tag-${UUID.randomUUID().toString().take(8)}@$PHANTOM_DOMAIN"

        fun ipKey(ip: String): String = "{scope:rate_admin_login}:{ip:${IpHasher.hash(ip)}}"

        fun emailKey(email: String): String = "{scope:rate_admin_login}:{email:${AdminAuditLogger.sha256Base64Url(email)}}"

        fun lockoutKey(email: String): String = "{scope:rate_admin_login_lockout}:{email:${AdminAuditLogger.sha256Base64Url(email)}}"

        /** Fill a burst-window bucket to [capacity] so the next attempt on it is throttled. */
        fun RateLimiter.exhaust(
            key: String,
            capacity: Int,
        ) = repeat(capacity) { tryAcquireByKey(key, capacity, AdminLoginRoutes.BURST_WINDOW) }

        /** WARN+ lines the AdminAuditLogger emitted while [block] ran. */
        suspend fun captureAuditWarnings(block: suspend () -> Unit): List<String> {
            val logger = LoggerFactory.getLogger(AdminAuditLogger::class.java.name) as LogbackLogger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                block()
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
            return appender.list.filter { it.level.isGreaterOrEqual(Level.WARN) }.map { it.formattedMessage }
        }
    }
}
