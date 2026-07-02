package id.nearyou.app.referral

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import id.nearyou.app.infra.revenuecatapi.GrantRequest
import id.nearyou.app.infra.revenuecatapi.GrantResult
import id.nearyou.app.infra.revenuecatapi.NoOpReferralEntitlementGranter
import id.nearyou.app.infra.revenuecatapi.ReferralEntitlementGranter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private fun hikari(): HikariDataSource {
    val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
    val user = System.getenv("DB_USER") ?: "postgres"
    val password = System.getenv("DB_PASSWORD") ?: "postgres"
    return HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 4 // a couple of concurrent-worker tests need > 2 connections
            initializationFailTimeout = -1
        },
    )
}

/** Records every dispatched [GrantRequest]; optionally throws for one app-user id. */
private class FakeGranter(
    private val configured: Boolean = true,
    private val throwOnAppUserId: String? = null,
) : ReferralEntitlementGranter {
    val requests: MutableList<GrantRequest> = Collections.synchronizedList(mutableListOf())

    override fun isConfigured(): Boolean = configured

    override suspend fun grant(request: GrantRequest): GrantResult {
        requests += request
        if (request.appUserId == throwOnAppUserId) error("simulated RevenueCat failure")
        return GrantResult.Dispatched
    }
}

@Tags("database")
class ReferralActivityCheckWorkerTest : StringSpec({

    // NOT autoClose: afterSpec runs cleanSlate (which needs the pool) before closing it,
    // so this spec leaves no posts/users behind to pollute the timeline suites in a full run.
    val dataSource = hikari()
    val seq = AtomicInteger(0)
    // Fixed clock for stacking-window assertions; expiry uses SQL NOW() (days apart, so the
    // few-seconds skew is irrelevant). Micros truncation matches Postgres timestamptz (CI parity).
    val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    fun ts(instant: Instant) = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    // Scope deletes to THIS spec's users (rgw%) so a full multi-spec run never
    // FK-violates on another spec's users (e.g. their refresh_tokens). The users
    // delete cascades referral_tickets + granted_entitlements (V23/V29 ON DELETE
    // CASCADE); posts + subscription_events (RESTRICT / NO ACTION) go first.
    fun cleanSlate() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM posts WHERE author_id IN (SELECT id FROM users WHERE username LIKE 'rgw%')")
                st.executeUpdate("DELETE FROM subscription_events WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'rgw%')")
                st.executeUpdate("DELETE FROM users WHERE username LIKE 'rgw%'")
            }
        }
    }

    fun seedUser(
        id: UUID = UUID.randomUUID(),
        isBanned: Boolean = false,
        isShadowBanned: Boolean = false,
        inviterRewardClaimed: Boolean = false,
    ): UUID {
        val n = seq.incrementAndGet()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (id, username, display_name, date_of_birth, invite_code_prefix,
                                   is_banned, is_shadow_banned, inviter_reward_claimed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, "rgw_${n}_${id.toString().take(6)}")
                ps.setString(3, "RGW $n")
                ps.setObject(4, LocalDate.of(1990, 1, 1))
                ps.setString(5, "%08d".format(n)) // invite_code_prefix is VARCHAR(8)
                ps.setBoolean(6, isBanned)
                ps.setBoolean(7, isShadowBanned)
                if (inviterRewardClaimed) ps.setObject(8, ts(now)) else ps.setNull(8, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                ps.executeUpdate()
            }
        }
        return id
    }

    fun seedPosts(
        authorId: UUID,
        count: Int,
        createdAt: Instant = now,
    ) {
        dataSource.connection.use { conn ->
            repeat(count) {
                conn.prepareStatement(
                    """
                    INSERT INTO posts (author_id, content, display_location, actual_location, created_at)
                    VALUES (?, ?, ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326)::geography,
                            ST_SetSRID(ST_MakePoint(106.8, -6.2), 4326)::geography, ?)
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, authorId)
                    ps.setString(2, "post ${seq.incrementAndGet()}")
                    ps.setObject(3, ts(createdAt))
                    ps.executeUpdate()
                }
            }
        }
    }

    /** Insert one `login_events` row (V35) per instant for [userId]. */
    fun seedLoginEvents(
        userId: UUID,
        instants: List<Instant>,
        fingerprint: String? = null,
        identifier: String? = null,
        ip: String? = null,
    ) {
        dataSource.connection.use { conn ->
            instants.forEach { at ->
                conn.prepareStatement(
                    "INSERT INTO login_events (user_id, occurred_at, event_type, ip, " +
                        "device_fingerprint_hash, identifier_hash) VALUES (?, ?, 'signin', ?::inet, ?, ?)",
                ).use { ps ->
                    ps.setObject(1, userId)
                    ps.setObject(2, ts(at))
                    ps.setString(3, ip)
                    ps.setString(4, fingerprint)
                    ps.setString(5, identifier)
                    ps.executeUpdate()
                }
            }
        }
    }

    // WIB-noon anchor: seeded activity sits at 12:00 Asia/Jakarta on recent days, so day-bucketing
    // and 30-min sessionization are deterministic (never near a midnight boundary), independent of
    // the wall-clock `now`. Whole-day-apart noon anchors are always distinct WIB dates.
    val wib: java.time.ZoneId = java.time.ZoneId.of("Asia/Jakarta")

    fun wibNoonDaysAgo(days: Long): Instant = now.atZone(wib).toLocalDate().minusDays(days).atTime(12, 0).atZone(wib).toInstant()

    /** Set the invitee's signup fingerprint + provider identity (the leg-3 / leg-5 inputs). */
    fun setUserIdentity(
        userId: UUID,
        fingerprint: String?,
        googleIdHash: String?,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "UPDATE users SET device_fingerprint_hash = ?, google_id_hash = ? WHERE id = ?",
            ).use { ps ->
                ps.setString(1, fingerprint)
                ps.setString(2, googleIdHash)
                ps.setObject(3, userId)
                ps.executeUpdate()
            }
        }
    }

    /**
     * Seed login_events that clear BOTH engagement legs: 5 sessions across ≥ 3 distinct
     * Asia/Jakarta days (events 60/36/12h, then 2/1h before `now` — each pair > 30 min apart, and
     * the 24h-spaced first three guarantee 3 distinct WIB dates). Called by default from
     * [seedTicket] so a posts-passing ticket also clears the login-history legs; the per-invitee
     * fingerprint never matches the (history-less) inviter, so the anti-collision legs stay clean.
     */
    fun seedPassingActivity(inviteeId: UUID) {
        seedLoginEvents(
            inviteeId,
            listOf(60L, 36L, 12L, 2L, 1L).map { now.minus(Duration.ofHours(it)) },
            fingerprint = "fp_passing_${inviteeId.toString().take(6)}",
        )
    }

    /**
     * Seed one pending ticket. Returns the ticket id. By default also seeds passing login-history
     * activity for the invitee (so a posts-passing ticket clears the engagement legs); pass
     * `seedInviteeActivity = false` to control `login_events` explicitly (the leg-specific tests).
     */
    fun seedTicket(
        inviterId: UUID,
        inviteeId: UUID,
        createdAt: Instant = now.minus(Duration.ofDays(3)),
        expiresAt: Instant = now.plus(Duration.ofDays(11)),
        seedInviteeActivity: Boolean = true,
    ): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO referral_tickets (id, inviter_user_id, invitee_user_id, status, created_at, expires_at)
                VALUES (?, ?, ?, 'pending_activity', ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, inviterId)
                ps.setObject(3, inviteeId)
                ps.setObject(4, ts(createdAt))
                ps.setObject(5, ts(expiresAt))
                ps.executeUpdate()
            }
        }
        if (seedInviteeActivity) seedPassingActivity(inviteeId)
        return id
    }

    fun seedEntitlement(
        userId: UUID,
        entitlementEnd: Instant,
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO subscription_events (user_id, event_type, source, revenuecat_event_id, entitlement_end)
                VALUES (?, 'initial_purchase', 'paid', ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, userId)
                ps.setString(2, "seed_${UUID.randomUUID()}")
                ps.setObject(3, ts(entitlementEnd))
                ps.executeUpdate()
            }
        }
    }

    fun <T> query(
        sql: String,
        bind: (java.sql.PreparedStatement) -> Unit = {},
        read: (java.sql.ResultSet) -> T,
    ): T =
        dataSource.connection.use { conn: Connection ->
            conn.prepareStatement(sql).use { ps ->
                bind(ps)
                ps.executeQuery().use { rs ->
                    rs.next()
                    read(rs)
                }
            }
        }

    fun ticketStatus(id: UUID): String =
        query("SELECT status FROM referral_tickets WHERE id = ?", { it.setObject(1, id) }) { it.getString(1) }

    // Scoped to this spec's users (rgw%) so a future spec leaving granted_entitlements
    // rows can't pollute the "no grant" assertions (granted_entitlements is mine-only today).
    fun grantCount(): Int =
        query(
            "SELECT COUNT(*) FROM granted_entitlements ge JOIN users u ON ge.user_id = u.id WHERE u.username LIKE 'rgw%'",
        ) { it.getInt(1) }

    fun grantCountFor(
        userId: UUID,
        role: String,
    ): Int =
        query(
            "SELECT COUNT(*) FROM granted_entitlements WHERE user_id = ? AND grant_role = ?",
            {
                it.setObject(1, userId)
                it.setString(2, role)
            },
        ) { it.getInt(1) }

    fun inviterSentinelSet(userId: UUID): Boolean =
        query(
            "SELECT inviter_reward_claimed_at IS NOT NULL FROM users WHERE id = ?",
            { it.setObject(1, userId) },
        ) { it.getBoolean(1) }

    fun subscriptionStatus(userId: UUID): String =
        query("SELECT subscription_status FROM users WHERE id = ?", { it.setObject(1, userId) }) { it.getString(1) }

    fun worker(granter: ReferralEntitlementGranter): ReferralActivityCheckWorker =
        ReferralActivityCheckWorker(
            dataSource = dataSource,
            repository = ReferralGrantRepository(),
            granter = granter,
            clock = { now },
            dbDispatcher = Dispatchers.IO,
        )

    beforeEach { cleanSlate() }
    afterSpec {
        cleanSlate()
        dataSource.close()
    }

    // ---------- 6.2 expiry ----------
    "a pending ticket past expires_at is expired with no grant" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        val ticket = seedTicket(inviter, invitee, expiresAt = now.minus(Duration.ofDays(1)))

        worker(FakeGranter()).execute()

        ticketStatus(ticket) shouldBe "expired"
        grantCount() shouldBe 0
    }

    // ---------- 6.3 activity gate ----------
    "invitee with exactly 2 posts and a good-standing inviter passes (>= boundary)" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        val ticket = seedTicket(inviter, invitee)

        val result = worker(FakeGranter()).execute()

        ticketStatus(ticket) shouldBe "granted"
        result.granted shouldBe 1
    }

    "invitee with fewer than 2 posts stays pending" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 1)
        val ticket = seedTicket(inviter, invitee)

        worker(FakeGranter()).execute()

        ticketStatus(ticket) shouldBe "pending_activity"
        grantCount() shouldBe 0
    }

    "a hard-banned inviter voids the ticket (distinct 'voided' status, not 'expired')" {
        val inviter = seedUser(isBanned = true)
        val invitee = seedUser()
        seedPosts(invitee, 3)
        val ticket = seedTicket(inviter, invitee)

        worker(FakeGranter()).execute()

        ticketStatus(ticket) shouldBe "voided"
        grantCount() shouldBe 0
    }

    "a shadow-banned inviter voids the ticket (distinct 'voided' status, not 'expired')" {
        val inviter = seedUser(isShadowBanned = true)
        val invitee = seedUser()
        seedPosts(invitee, 3)
        val ticket = seedTicket(inviter, invitee)

        worker(FakeGranter()).execute()

        ticketStatus(ticket) shouldBe "voided"
        grantCount() shouldBe 0
    }

    // ---------- reconcile pass (re-dispatch failed grants) ----------
    "the reconcile pass re-dispatches a grant whose prior RC call failed and stamps it dispatched" {
        val inviter = seedUser()
        val invitee = seedUser()
        val ticket = seedTicket(inviter, invitee)
        // Simulate a prior run: ledger row committed but RC dispatch failed (dispatched_at NULL),
        // and the ticket already flipped to 'granted' (so pass 2 skips it).
        dataSource.connection.use { conn ->
            ReferralGrantRepository().insertGrantIfNew(
                conn,
                ticket,
                invitee,
                "invitee",
                now,
                now.plus(Duration.ofDays(7)),
                "$ticket:invitee",
            )
            conn.prepareStatement("UPDATE referral_tickets SET status = 'granted' WHERE id = ?").use {
                it.setObject(1, ticket)
                it.executeUpdate()
            }
        }
        val granter = FakeGranter()

        worker(granter).execute()

        // pass 3 re-dispatched the un-dispatched grant... (scoped to this dedup_key — the
        // reconcile scan is global, so an exact `reconciled` count would be multi-spec-fragile)
        granter.requests.any { it.dedupKey == "$ticket:invitee" } shouldBe true
        // ...and stamped it dispatched so the next run skips it.
        query(
            "SELECT COUNT(*) FROM granted_entitlements WHERE dedup_key = ? AND revenuecat_dispatched_at IS NOT NULL",
            { it.setString(1, "$ticket:invitee") },
        ) { it.getInt(1) } shouldBe 1
    }

    // ---------- 6.4 invitee grant + dispatch ----------
    "a passing ticket grants the invitee and dispatches the RevenueCat grant" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        val ticket = seedTicket(inviter, invitee)
        val granter = FakeGranter()

        worker(granter).execute()

        ticketStatus(ticket) shouldBe "granted"
        grantCountFor(invitee, "invitee") shouldBe 1
        granter.requests shouldHaveSize 1
        granter.requests[0].appUserId shouldBe invitee.toString()
        granter.requests[0].entitlementId shouldBe "premium"
        granter.requests[0].dedupKey shouldBe "$ticket:invitee"
    }

    // ---------- 6.5 stacking ----------
    "a premium_active recipient's window extends by 7 days" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        seedEntitlement(invitee, now.plus(Duration.ofDays(30)))
        seedTicket(inviter, invitee)

        worker(FakeGranter()).execute()

        val end =
            query(
                "SELECT entitlement_end FROM granted_entitlements WHERE user_id = ?",
                { it.setObject(1, invitee) },
            ) { it.getObject(1, OffsetDateTime::class.java).toInstant() }
        end shouldBe now.plus(Duration.ofDays(37))
    }

    "a free/lapsed recipient gets a fresh 7-day window" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        // lapsed: an entitlement that already ended in the past -> floor at NOW(), not end+7d
        seedEntitlement(invitee, now.minus(Duration.ofDays(10)))
        seedTicket(inviter, invitee)

        worker(FakeGranter()).execute()

        val end =
            query(
                "SELECT entitlement_end FROM granted_entitlements WHERE user_id = ?",
                { it.setObject(1, invitee) },
            ) { it.getObject(1, OffsetDateTime::class.java).toInstant() }
        end shouldBe now.plus(Duration.ofDays(7))
    }

    "a second grant for the same user in one run stacks off the first granted_entitlements row" {
        // U is both an invitee (own ticket) AND an inviter hitting their 5th referral in the
        // SAME run. The inviter grant must stack off U's just-written invitee grant (whose
        // RevenueCat echo has not arrived) — i.e. currentEntitlementEnd reads granted_entitlements,
        // not only subscription_events. U's invitee ticket is oldest → granted first.
        val u = seedUser()
        val uInviter = seedUser()
        seedPosts(u, 2)
        seedTicket(uInviter, u, createdAt = now.minus(Duration.ofDays(20)))
        repeat(5) { i ->
            val invitee = seedUser()
            seedPosts(invitee, 2)
            seedTicket(u, invitee, createdAt = now.minus(Duration.ofDays((10 - i).toLong())))
        }

        worker(FakeGranter()).execute()

        fun grantEnd(role: String): Instant =
            query(
                "SELECT entitlement_end FROM granted_entitlements WHERE user_id = ? AND grant_role = ?",
                { ps ->
                    ps.setObject(1, u)
                    ps.setString(2, role)
                },
            ) { it.getObject(1, OffsetDateTime::class.java).toInstant() }

        grantEnd("invitee") shouldBe now.plus(Duration.ofDays(7))
        grantEnd("inviter") shouldBe now.plus(Duration.ofDays(14)) // stacked off the invitee grant
    }

    // ---------- 6.6 invitee idempotency ----------
    "re-running the worker over a granted ticket creates no second invitee grant" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        seedTicket(inviter, invitee)

        worker(FakeGranter()).execute()
        worker(FakeGranter()).execute() // second run: ticket already 'granted', not in the pending scan

        grantCountFor(invitee, "invitee") shouldBe 1
    }

    // ---------- 6.7 inviter lifetime cap (docs/08 §292 verbatim) ----------
    "10 successful referrals grant the inviter exactly once, at the 5th ticket, zero after" {
        val inviter = seedUser()
        val ticketsInOrder =
            (1..10).map { i ->
                val invitee = seedUser()
                seedPosts(invitee, 2)
                // distinct created_at so the worker processes them in a deterministic order;
                // all >= 3 days old so each invitee's auto-seeded activity clears the 3-login-day leg
                seedTicket(inviter, invitee, createdAt = now.minus(Duration.ofDays(13 - i.toLong())))
            }

        worker(FakeGranter()).execute()

        grantCountFor(inviter, "inviter") shouldBe 1
        inviterSentinelSet(inviter) shouldBe true
        // the single inviter grant is tied to the 5th ticket
        val inviterGrantTicket =
            query(
                "SELECT referral_ticket_id FROM granted_entitlements WHERE user_id = ? AND grant_role = 'inviter'",
                { it.setObject(1, inviter) },
            ) { it.getObject(1, UUID::class.java) }
        inviterGrantTicket shouldBe ticketsInOrder[4]
    }

    "referrals one through four grant the inviter nothing" {
        val inviter = seedUser()
        repeat(4) {
            val invitee = seedUser()
            seedPosts(invitee, 2)
            seedTicket(inviter, invitee, createdAt = now.minus(Duration.ofDays((10 - it).toLong())))
        }

        worker(FakeGranter()).execute()

        grantCountFor(inviter, "inviter") shouldBe 0
        inviterSentinelSet(inviter) shouldBe false
    }

    // ---------- 6.8 concurrent worker runs ----------
    "two concurrent worker runs grant each invitee once and the inviter at most once" {
        val inviter = seedUser()
        repeat(5) { i ->
            val invitee = seedUser()
            seedPosts(invitee, 2)
            seedTicket(inviter, invitee, createdAt = now.minus(Duration.ofDays((10 - i).toLong())))
        }

        coroutineScope {
            val a = async(Dispatchers.IO) { worker(FakeGranter()).execute() }
            val b = async(Dispatchers.IO) { worker(FakeGranter()).execute() }
            a.await()
            b.await()
        }

        // 5 invitee grants (one per ticket) + exactly 1 inviter grant — no duplicates from the race.
        grantCountFor(inviter, "inviter") shouldBe 1
        query("SELECT COUNT(*) FROM granted_entitlements WHERE grant_role = 'invitee'") { it.getInt(1) } shouldBe 5
    }

    // ---------- 6.9 schema constraints ----------
    "the inviter-once partial index rejects a second inviter grant for the same user" {
        val inviter = seedUser()
        val invitee = seedUser()
        val t1 = seedTicket(inviter, invitee)
        val invitee2 = seedUser()
        val t2 = seedTicket(inviter, invitee2)
        dataSource.connection.use { conn ->
            fun insertInviterGrant(ticket: UUID): Boolean =
                ReferralGrantRepository().insertGrantIfNew(
                    conn,
                    ticket,
                    inviter,
                    "inviter",
                    now,
                    now.plus(Duration.ofDays(7)),
                    "$ticket:inviter",
                )
            insertInviterGrant(t1) shouldBe true
            insertInviterGrant(t2) shouldBe false // partial-unique index blocks the second inviter row
        }
    }

    "the grant_role CHECK rejects an out-of-vocabulary value" {
        val inviter = seedUser()
        val invitee = seedUser()
        val ticket = seedTicket(inviter, invitee)
        shouldThrow<java.sql.SQLException> {
            dataSource.connection.use { conn ->
                conn.prepareStatement(
                    "INSERT INTO granted_entitlements " +
                        "(referral_ticket_id, user_id, grant_role, entitlement_start, entitlement_end, dedup_key) " +
                        "VALUES (?, ?, 'sponsor', ?, ?, ?)",
                ).use { ps ->
                    ps.setObject(1, ticket)
                    ps.setObject(2, invitee)
                    ps.setObject(3, ts(now))
                    ps.setObject(4, ts(now.plus(Duration.ofDays(7))))
                    ps.setString(5, "$ticket:sponsor")
                    ps.executeUpdate()
                }
            }
        }
    }

    "a user hard-delete cascades their granted_entitlements rows" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        seedTicket(inviter, invitee)
        worker(FakeGranter()).execute()
        grantCountFor(invitee, "invitee") shouldBe 1

        // Delete the invitee's posts (RESTRICT FK) then the invitee row; the granted_entitlements
        // row cascades via the user_id (and referral_ticket_id) ON DELETE CASCADE FKs.
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM posts WHERE author_id = ?").use { ps ->
                ps.setObject(1, invitee)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM users WHERE id = ?").use { ps ->
                ps.setObject(1, invitee)
                ps.executeUpdate()
            }
        }
        grantCountFor(invitee, "invitee") shouldBe 0
    }

    // ---------- 6.11 fail-soft (key unset) ----------
    "with the RC client unconfigured a passing ticket is still granted and the worker does not throw" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        val ticket = seedTicket(inviter, invitee)

        worker(NoOpReferralEntitlementGranter).execute() // must not throw

        ticketStatus(ticket) shouldBe "granted"
        grantCountFor(invitee, "invitee") shouldBe 1
    }

    // ---------- 6.13 worker does not write subscription_status ----------
    "the worker does not touch subscription_status (the webhook GRANT echo owns it)" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        seedTicket(inviter, invitee)

        worker(FakeGranter()).execute()

        subscriptionStatus(invitee) shouldBe "free" // default; unchanged by the worker
    }

    // ---------- 6.14 login-history activity-gate legs (login-history-tracking) ----------
    "an invitee below the 3-login-day threshold stays pending (sessions met, anti-collision clean)" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        val ticket = seedTicket(inviter, invitee, createdAt = wibNoonDaysAgo(8), seedInviteeActivity = false)
        // 5 sessions but only 2 distinct WIB days → the login-days leg (>= 3) fails.
        seedLoginEvents(
            invitee,
            listOf(
                wibNoonDaysAgo(2),
                wibNoonDaysAgo(2).plus(Duration.ofHours(1)),
                wibNoonDaysAgo(2).plus(Duration.ofHours(2)),
                wibNoonDaysAgo(3),
                wibNoonDaysAgo(3).plus(Duration.ofHours(1)),
            ),
        )
        worker(FakeGranter()).execute()
        ticketStatus(ticket) shouldBe "pending_activity"
        grantCount() shouldBe 0
    }

    "an invitee below the 5-session threshold stays pending (login-days met, anti-collision clean)" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        val ticket = seedTicket(inviter, invitee, createdAt = wibNoonDaysAgo(8), seedInviteeActivity = false)
        // 3 sessions across 3 distinct WIB days → login-days met (3) but the sessions leg (>= 5) fails.
        seedLoginEvents(invitee, listOf(wibNoonDaysAgo(2), wibNoonDaysAgo(3), wibNoonDaysAgo(4)))
        worker(FakeGranter()).execute()
        ticketStatus(ticket) shouldBe "pending_activity"
        grantCount() shouldBe 0
    }

    "an invitee exactly meeting 3 login-days and 5 sessions passes (inclusive boundary)" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        val ticket = seedTicket(inviter, invitee, createdAt = wibNoonDaysAgo(8), seedInviteeActivity = false)
        // exactly 5 sessions across exactly 3 distinct WIB days.
        seedLoginEvents(
            invitee,
            listOf(
                wibNoonDaysAgo(2),
                wibNoonDaysAgo(2).plus(Duration.ofHours(1)),
                wibNoonDaysAgo(2).plus(Duration.ofHours(2)),
                wibNoonDaysAgo(3),
                wibNoonDaysAgo(4),
            ),
        )
        worker(FakeGranter()).execute()
        ticketStatus(ticket) shouldBe "granted"
    }

    "sessionization: events within 30 minutes collapse to one session (count falls short → pending)" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        val ticket = seedTicket(inviter, invitee, createdAt = wibNoonDaysAgo(8), seedInviteeActivity = false)
        // 3 distinct days; on each day a second event 29 min later does NOT add a session → 3 sessions (< 5).
        seedLoginEvents(
            invitee,
            listOf(2L, 3L, 4L).flatMap { d -> listOf(wibNoonDaysAgo(d), wibNoonDaysAgo(d).plus(Duration.ofMinutes(29))) },
        )
        worker(FakeGranter()).execute()
        ticketStatus(ticket) shouldBe "pending_activity"
    }

    "sessionization: an idle gap over 30 minutes starts a new session (count met → granted)" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        val ticket = seedTicket(inviter, invitee, createdAt = wibNoonDaysAgo(8), seedInviteeActivity = false)
        // 3 distinct days; on two of them a second event 31 min later IS a new session → 5 sessions.
        seedLoginEvents(
            invitee,
            listOf(
                wibNoonDaysAgo(2),
                wibNoonDaysAgo(2).plus(Duration.ofMinutes(31)),
                wibNoonDaysAgo(3),
                wibNoonDaysAgo(3).plus(Duration.ofMinutes(31)),
                wibNoonDaysAgo(4),
            ),
        )
        worker(FakeGranter()).execute()
        ticketStatus(ticket) shouldBe "granted"
    }

    "login-days are bucketed in Asia/Jakarta, not UTC (3 WIB days that span only 2 UTC days passes)" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        val ticket = seedTicket(inviter, invitee, createdAt = wibNoonDaysAgo(8), seedInviteeActivity = false)
        // 5 events on 3 distinct WIB days (X, X+1, X+2) but only 2 distinct UTC days: noon+9h/+10h =
        // WIB 21:00/22:00, noon-10h = WIB 02:00 (which sits on the PRIOR UTC day, since WIB = UTC+7).
        // If bucketing used UTC it would see only 2 days → pending; granted proves WIB bucketing.
        val wibX = wibNoonDaysAgo(5)
        val wibX1 = wibNoonDaysAgo(4)
        val wibX2 = wibNoonDaysAgo(3)
        seedLoginEvents(
            invitee,
            listOf(
                wibX.plus(Duration.ofHours(9)),
                wibX.plus(Duration.ofHours(10)),
                wibX1.minus(Duration.ofHours(10)),
                wibX1.plus(Duration.ofHours(10)),
                wibX2.minus(Duration.ofHours(10)),
            ),
        )
        worker(FakeGranter()).execute()
        ticketStatus(ticket) shouldBe "granted"
    }

    "a device-fingerprint collision with the inviter's 90-day history voids the ticket" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        setUserIdentity(invitee, fingerprint = "fp-shared", googleIdHash = "g-inv-${invitee.toString().take(8)}")
        // the inviter logged in from the same device fingerprint within 90 days.
        seedLoginEvents(inviter, listOf(wibNoonDaysAgo(10)), fingerprint = "fp-shared")
        val ticket = seedTicket(inviter, invitee, createdAt = wibNoonDaysAgo(8)) // passing engagement auto-seeded
        worker(FakeGranter()).execute()
        ticketStatus(ticket) shouldBe "voided"
        grantCount() shouldBe 0
    }

    "an IP /24 overlap, with no fingerprint or identifier collision, does NOT void (recorded only, D8a)" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        setUserIdentity(invitee, fingerprint = "fp-invitee-clean", googleIdHash = "g-inv-${invitee.toString().take(8)}")
        // inviter + invitee share a /24 (203.0.113.0/24) but NOT a fingerprint or identity.
        seedLoginEvents(inviter, listOf(wibNoonDaysAgo(10)), fingerprint = "fp-inviter", ip = "203.0.113.5")
        val ticket = seedTicket(inviter, invitee, createdAt = wibNoonDaysAgo(8), seedInviteeActivity = false)
        seedLoginEvents(
            invitee,
            listOf(
                wibNoonDaysAgo(2),
                wibNoonDaysAgo(2).plus(Duration.ofHours(1)),
                wibNoonDaysAgo(2).plus(Duration.ofHours(2)),
                wibNoonDaysAgo(3),
                wibNoonDaysAgo(4),
            ),
            fingerprint = "fp-invitee-clean",
            ip = "203.0.113.9",
        )
        worker(FakeGranter()).execute()
        ticketStatus(ticket) shouldBe "granted" // the shared /24 is recorded but never voids
    }

    "the invitee's identity seen on an inviter device voids the ticket (recently-seen identifier)" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        val inviteeIdentity = "g-inv-${invitee.toString().take(8)}"
        // the invitee's signup fingerprint does NOT collide (isolating leg 5 from leg 3).
        setUserIdentity(invitee, fingerprint = "fp-invitee-clean", googleIdHash = inviteeIdentity)
        seedLoginEvents(inviter, listOf(wibNoonDaysAgo(10)), fingerprint = "fp-inviter-dev")
        // the invitee's account was logged in on the inviter's device (identity on the inviter's fingerprint).
        seedLoginEvents(invitee, listOf(wibNoonDaysAgo(9)), fingerprint = "fp-inviter-dev", identifier = inviteeIdentity)
        val ticket = seedTicket(inviter, invitee, createdAt = wibNoonDaysAgo(8)) // passing engagement auto-seeded
        worker(FakeGranter()).execute()
        ticketStatus(ticket) shouldBe "voided"
        grantCount() shouldBe 0
    }

    "an anti-collision collision voids even when the invitee is below an engagement threshold (precedence)" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        setUserIdentity(invitee, fingerprint = "fp-shared", googleIdHash = "g-inv-${invitee.toString().take(8)}")
        seedLoginEvents(inviter, listOf(wibNoonDaysAgo(10)), fingerprint = "fp-shared")
        // only 1 login-day (below engagement) AND a fingerprint collision → void wins over pending.
        val ticket = seedTicket(inviter, invitee, createdAt = wibNoonDaysAgo(8), seedInviteeActivity = false)
        seedLoginEvents(invitee, listOf(wibNoonDaysAgo(2)))
        worker(FakeGranter()).execute()
        ticketStatus(ticket) shouldBe "voided"
        grantCount() shouldBe 0
    }

    "an inviter with no login history never false-voids the ticket (collision needs a positive match)" {
        val inviter = seedUser()
        val invitee = seedUser()
        seedPosts(invitee, 2)
        // the invitee has a fingerprint + identity set, but the inviter has NO login_events.
        setUserIdentity(invitee, fingerprint = "fp-invitee", googleIdHash = "g-inv-${invitee.toString().take(8)}")
        val ticket = seedTicket(inviter, invitee, createdAt = wibNoonDaysAgo(8)) // passing engagement auto-seeded
        worker(FakeGranter()).execute()
        ticketStatus(ticket) shouldBe "granted"
    }

    // ---------- 6.15 partial-failure isolation ----------
    "a throwing RC client does not halt the batch or roll back the committed grant" {
        val inviter = seedUser()
        val inviteeA = seedUser()
        val inviteeB = seedUser()
        seedPosts(inviteeA, 2)
        seedPosts(inviteeB, 2)
        val ticketA = seedTicket(inviter, inviteeA, createdAt = now.minus(Duration.ofDays(5)))
        val ticketB = seedTicket(inviter, inviteeB, createdAt = now.minus(Duration.ofDays(4)))

        // The client throws on inviteeA's dispatch; the grant is already committed by then.
        worker(FakeGranter(throwOnAppUserId = inviteeA.toString())).execute()

        ticketStatus(ticketA) shouldBe "granted" // committed despite the dispatch throw
        ticketStatus(ticketB) shouldBe "granted" // batch was not halted
        grantCountFor(inviteeA, "invitee") shouldBe 1
        grantCountFor(inviteeB, "invitee") shouldBe 1
    }
})
