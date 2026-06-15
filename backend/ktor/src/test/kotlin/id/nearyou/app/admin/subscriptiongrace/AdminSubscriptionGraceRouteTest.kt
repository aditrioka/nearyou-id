package id.nearyou.app.admin.subscriptiongrace

import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.admin.auth.AdminAuthProvider
import id.nearyou.app.admin.auth.AdminAuthTestSupport
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.time.Instant
import java.util.UUID

/**
 * Integration tests for the `admin-subscription-grace-monitor` surface
 * (`GET /admin/subscriptions/grace` + `POST .../{user_id}/expedite`), tasks.md
 * Section 5. DB-backed; tagged `database`. Uses `AdminAuthTestSupport` for the
 * authenticated-session wiring + `GraceTestSupport` for `users` /
 * `subscription_events` / `admin_actions_log` seeding.
 *
 * Covers: the auth gate, the billing-retry list + exclusions + empty-state-
 * tolerant no-event rows, lenient/injection-safe filters, the count summary,
 * PII discipline + output escaping, the expedite bookkeeping write (entitlement
 * unchanged), the ticket-ref requirement, repeat-append, the CSRF + role guards,
 * the distinct rate-limit cap + budget independence + per-admin scoping, the
 * already-expedited indicator (latest of N), and the target-state rejection.
 */
@Tags("database")
class AdminSubscriptionGraceRouteTest : StringSpec({

    val dataSource: HikariDataSource = AdminAuthTestSupport.hikari()
    afterSpec { dataSource.close() }

    val seededUsers = mutableListOf<UUID>()
    val seededAdmins = mutableListOf<UUID>()
    afterEach {
        seededUsers.forEach { GraceTestSupport.deleteUser(dataSource, it) }
        seededUsers.clear()
        seededAdmins.forEach { AdminAuthTestSupport.cleanupAdmin(dataSource, it) }
        seededAdmins.clear()
    }

    fun seedAdmin(role: String = "owner") = AdminAuthTestSupport.seedAdmin(dataSource, role = role).also { seededAdmins.add(it.id) }

    fun seedUser(
        username: String,
        status: String = STATUS_BILLING_RETRY,
        deletedAt: Instant? = null,
        displayName: String = "Grace Tester",
    ): UUID = GraceTestSupport.seedUser(dataSource, username, status, displayName, deletedAt).also { seededUsers.add(it) }

    fun cookie(token: String) = "${AdminAuthProvider.COOKIE_NAME}=$token"

    // ---- Req 1: the billing-retry list ----

    "5.1 — authenticated GET lists a billing-retry user with store + last webhook + base layout" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("rina_sore")
        GraceTestSupport.seedEvent(dataSource, u, "billing_issue", platform = "app_store")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/subscriptions/grace") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            val body = res.bodyAsText()
            body shouldContain "rina_sore"
            body shouldContain "app_store"
            body shouldContain "billing_issue"
            body shouldContain "<header>"
            body shouldContain "<nav>"
        }
    }

    "5.1 — active/free/soft-deleted users are excluded; only billing-retry lists" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("retry_me", STATUS_BILLING_RETRY)
        seedUser("active_user", "premium_active")
        seedUser("free_user", "free")
        seedUser("deleted_retry", STATUS_BILLING_RETRY, deletedAt = Instant.now().minusSeconds(60))

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/subscriptions/grace") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "retry_me"
            body shouldNotContain "active_user"
            body shouldNotContain "free_user"
            body shouldNotContain "deleted_retry"
        }
    }

    "5.1 — a billing-retry user with no subscription_events still renders" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("no_events_user") // no seedEvent

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/subscriptions/grace") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "no_events_user"
        }
    }

    "5.1 — unauthenticated GET redirects (302) to /admin/login" {
        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/subscriptions/grace")
            res.status shouldBe HttpStatusCode.Found
            res.headers[HttpHeaders.Location] shouldBe "/admin/login"
        }
    }

    // ---- Req 2: filters + count + injection safety ----

    "5.2 — q lookup by username; blank q ignored (full population)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("budi_kopi")
        seedUser("rina_sore")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val filtered = client.get("/admin/subscriptions/grace?q=budi_kopi") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            filtered shouldContain "budi_kopi"
            filtered shouldNotContain "rina_sore"

            val blank = client.get("/admin/subscriptions/grace?q=") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            blank shouldContain "budi_kopi"
            blank shouldContain "rina_sore"
        }
    }

    "5.2 — store filter narrows; filters do not match a non-store user" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val appUser = seedUser("apple_user")
        GraceTestSupport.seedEvent(dataSource, appUser, "billing_issue", platform = "app_store")
        val playUser = seedUser("play_user")
        GraceTestSupport.seedEvent(dataSource, playUser, "billing_issue", platform = "play_store")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/subscriptions/grace?store=app_store") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "apple_user"
            body shouldNotContain "play_user"
        }
    }

    "5.2 — count summary reports the full billing-retry population" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        // Delta-based (robust to any pre-existing billing-retry rows in a shared dev DB).
        val baseline = GraceTestSupport.billingRetryCount(dataSource)
        seedUser("retry_a")
        seedUser("retry_b")
        seedUser("retry_c")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/subscriptions/grace") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "In billing-retry: ${baseline + 3}"
        }
    }

    "5.2 — SQL-metacharacter q is a literal (no injection); table survives" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("safe_user")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res = client.get("/admin/subscriptions/grace?q=%27%3B+DROP+TABLE+users%3B--") { header(HttpHeaders.Cookie, cookie(token)) }
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "No users in the billing-retry window"
            // users table still queryable (would throw if dropped)
            GraceTestSupport.subscriptionStatusOf(dataSource, seededUsers.first())
        }
    }

    // ---- Req 3: PII discipline + escaping ----

    "5.3 — no sensitive PII (DOB) rendered; username markup is escaped" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("xss_user", displayName = "irrelevant")
        // a separate user whose USERNAME carries markup
        val evil = GraceTestSupport.seedUser(dataSource, "<b>x</b>_user", STATUS_BILLING_RETRY).also { seededUsers.add(it) }
        GraceTestSupport.seedEvent(dataSource, evil, "billing_issue")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/subscriptions/grace") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "&lt;b&gt;x&lt;/b&gt;_user"
            body shouldNotContain "<b>x</b>_user"
            body shouldNotContain "1995" // seeded DOB year never rendered
        }
    }

    // ---- Req 4: expedite bookkeeping write ----

    "5.4 — successful expedite logs exactly one row; entitlement unchanged" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("expedite_me")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            // The per-row UI form is hx-post → exercise the HX path (re-renders the
            // table fragment with status OK). The no-JS path is a 303 PRG redirect.
            val res =
                client.post("/admin/subscriptions/grace/$u/expedite") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    header("HX-Request", "true")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("ticket_ref=SUP-2026-0142&note=user+paid+already")
                }
            res.status shouldBe HttpStatusCode.OK
            GraceTestSupport.countExpeditesFor(dataSource, u) shouldBe 1
            // Entitlement is UNCHANGED — RevenueCat remains the source of truth.
            GraceTestSupport.subscriptionStatusOf(dataSource, u) shouldBe STATUS_BILLING_RETRY
        }
    }

    "5.4 — successful expedite (no-JS path) is a 303 PRG redirect to the list" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("expedite_nojs")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/subscriptions/grace/$u/expedite") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("ticket_ref=SUP-NOJS")
                }
            res.status shouldBe HttpStatusCode.SeeOther
            res.headers[HttpHeaders.Location] shouldBe "/admin/subscriptions/grace"
            GraceTestSupport.countExpeditesFor(dataSource, u) shouldBe 1
        }
    }

    "5.4 — expedite without a ticket reference is rejected (400, no write)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("no_ticket")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/subscriptions/grace/$u/expedite") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("ticket_ref=&note=x")
                }
            res.status shouldBe HttpStatusCode.BadRequest
            GraceTestSupport.countExpeditesFor(dataSource, u) shouldBe 0
        }
    }

    "5.4 — repeat expedite of the same user appends a second row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("expedite_twice")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            repeat(2) { i ->
                client.post("/admin/subscriptions/grace/$u/expedite") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("ticket_ref=SUP-$i")
                }
            }
            GraceTestSupport.countExpeditesFor(dataSource, u) shouldBe 2
        }
    }

    // ---- Req 5: role + CSRF guards ----

    "5.5 — a read_only admin cannot expedite (403, no write)" {
        val admin = seedAdmin(role = "read_only")
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("ro_target")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/subscriptions/grace/$u/expedite") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("ticket_ref=SUP-1")
                }
            res.status shouldBe HttpStatusCode.Forbidden
            GraceTestSupport.countExpeditesFor(dataSource, u) shouldBe 0
        }
    }

    "5.5 — CSRF-token mismatch is 403 + audited, leaves the counter untouched (no write)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("csrf_target")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/subscriptions/grace/$u/expedite") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", "wrong-token")
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("ticket_ref=SUP-1")
                }
            res.status shouldBe HttpStatusCode.Forbidden
            GraceTestSupport.countExpeditesFor(dataSource, u) shouldBe 0
            val audit = AdminAuthTestSupport.latestAuditRows(dataSource, admin.id)
            audit.any { it.actionType == "admin_csrf_violation" } shouldBe true
            audit.any { it.actionType == "subscription_grace_expedite" } shouldBe false
        }
    }

    // ---- Req 6: distinct rate-limit cap ----

    "5.6 — the 21st expedite in an hour is rejected (quota), no write, status unchanged" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("capped_target")
        repeat(20) { GraceTestSupport.seedAuditRow(dataSource, admin.id, "subscription_grace_expedite") }

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/subscriptions/grace/$u/expedite") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("ticket_ref=SUP-21")
                }
            res.bodyAsText() shouldContain "quota exceeded"
            GraceTestSupport.countExpeditesFor(dataSource, u) shouldBe 0
            GraceTestSupport.subscriptionStatusOf(dataSource, u) shouldBe STATUS_BILLING_RETRY
        }
    }

    "5.6 — expedite succeeds even when the destructive budget is exhausted (independent counters)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("indep_target")
        // 20 destructive-set rows — must NOT block an expedite.
        repeat(20) { GraceTestSupport.seedAuditRow(dataSource, admin.id, "user_suspended") }

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/subscriptions/grace/$u/expedite") {
                header(HttpHeaders.Cookie, cookie(token))
                header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("ticket_ref=SUP-IND")
            }
            GraceTestSupport.countExpeditesFor(dataSource, u) shouldBe 1
        }
    }

    "5.6 — the cap is per-admin (admin B unaffected by admin A's exhausted quota)" {
        val adminA = seedAdmin()
        val adminB = seedAdmin()
        val tokenB = AdminAuthTestSupport.seedSession(dataSource, adminB.id)
        val u = seedUser("peradmin_target")
        repeat(20) { GraceTestSupport.seedAuditRow(dataSource, adminA.id, "subscription_grace_expedite") }

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/subscriptions/grace/$u/expedite") {
                header(HttpHeaders.Cookie, cookie(tokenB))
                header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(tokenB))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("ticket_ref=SUP-B")
            }
            GraceTestSupport.countExpeditesFor(dataSource, u) shouldBe 1
        }
    }

    // ---- Req 7: already-expedited indicator ----

    "5.6 — already-expedited row shows the handled indicator (latest of two); render writes no audit row" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val u = seedUser("handled_user")
        // Two prior expedites at distinct times — the indicator must show the NEWER.
        GraceTestSupport.seedAuditRow(
            dataSource,
            admin.id,
            "subscription_grace_expedite",
            targetId = u,
            createdAt = Instant.parse("2026-06-10T10:00:00Z"),
        )
        GraceTestSupport.seedAuditRow(
            dataSource,
            admin.id,
            "subscription_grace_expedite",
            targetId = u,
            createdAt = Instant.parse("2026-06-12T15:00:00Z"),
        )

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val auditBefore = AdminAuthTestSupport.countAuditRows(dataSource, admin.id)
            val body = client.get("/admin/subscriptions/grace") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "Expedited"
            body shouldContain "2026-06-12 15:00"
            body shouldNotContain "2026-06-10 10:00"
            // A read writes no audit row.
            AdminAuthTestSupport.countAuditRows(dataSource, admin.id) shouldBe auditBefore
        }
    }

    "5.6 — a never-expedited row offers the expedite control" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("fresh_user")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val body = client.get("/admin/subscriptions/grace") { header(HttpHeaders.Cookie, cookie(token)) }.bodyAsText()
            body shouldContain "Expedite resolution"
            body shouldContain "ticket_ref"
        }
    }

    // ---- Req 8: target-state rejection ----

    "5.8 — expedite against a non-billing-retry user is rejected (no write)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val active = seedUser("active_target", "premium_active")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            client.post("/admin/subscriptions/grace/$active/expedite") {
                header(HttpHeaders.Cookie, cookie(token))
                header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("ticket_ref=SUP-NE")
            }.bodyAsText() shouldContain "not in the billing-retry window"
            GraceTestSupport.countExpeditesFor(dataSource, active) shouldBe 0
            GraceTestSupport.subscriptionStatusOf(dataSource, active) shouldBe "premium_active"
        }
    }

    "5.8 — expedite against an unknown user id is rejected (no write)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        val unknown = UUID.randomUUID()

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.post("/admin/subscriptions/grace/$unknown/expedite") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("X-CSRF-Token", AdminAuthTestSupport.csrfFor(token))
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody("ticket_ref=SUP-UNK")
                }
            res.bodyAsText() shouldContain "not in the billing-retry window"
            GraceTestSupport.countExpeditesFor(dataSource, unknown) shouldBe 0
        }
    }

    "5.10 — HX-Request returns only the table fragment (no full-page layout)" {
        val admin = seedAdmin()
        val token = AdminAuthTestSupport.seedSession(dataSource, admin.id)
        seedUser("frag_user")

        AdminAuthTestSupport.withAdminApp(dataSource) { client ->
            val res =
                client.get("/admin/subscriptions/grace") {
                    header(HttpHeaders.Cookie, cookie(token))
                    header("HX-Request", "true")
                }
            val body = res.bodyAsText()
            body shouldContain "id=\"subscription-grace-table\""
            body shouldNotContain "<html"
            body shouldNotContain "<header>"
        }
    }
})
