package id.nearyou.app.admin.routes

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

/**
 * Pure-unit coverage for the dashboard's server-side formatting helpers
 * (`admin-operational-dashboard`): the sparkline bar-height precompute and the
 * DB-size byte formatter. No DB — runs in PR CI.
 */
class AdminDashboardFormatTest : StringSpec({

    "spark scales bar heights to the series max" {
        val s = spark(listOf(0L, 5L, 10L))
        s shouldBe listOf(0, 50, 100)
    }

    "spark floors a tiny non-zero count to a visible minimum, max stays 100" {
        val s = spark(listOf(0L, 1L, 100L))
        s[0] shouldBe 0
        (s[1] >= 8).shouldBeTrue() // 1/100 = 1% → floored to the visible minimum
        s[2] shouldBe 100
    }

    "spark snaps every bar to a 5% step, so each height is an admin.css .h-N class" {
        val s = spark(listOf(33L, 37L, 38L, 100L))
        s shouldBe listOf(35, 35, 40, 100)
        spark((1L..100L).toList()).all { it % SPARK_STEP_PCT == 0 }.shouldBeTrue()
    }

    "spark of an all-zero series is all zero (empty sparkline)" {
        spark(listOf(0L, 0L, 0L)) shouldBe listOf(0, 0, 0)
    }

    "spark of an empty series is empty" {
        spark(emptyList()) shouldBe emptyList()
    }

    "humanBytes formats binary units with an en-US decimal point" {
        humanBytes(512L) shouldBe "512 B"
        humanBytes(1024L) shouldBe "1.0 KB"
        humanBytes(1536L) shouldBe "1.5 KB"
        humanBytes(1024L * 1024) shouldBe "1.0 MB"
        humanBytes(5L * 1024 * 1024 * 1024) shouldBe "5.0 GB"
    }
})
