package id.nearyou.app.admin.routes

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * Boundary tests for the landing stat card's relative-age formatter
 * (`admin-mockup-parity` design.md D4 — server-side formatting, "2 h ago"
 * shape). Pure function; no DB tag.
 */
class RelativeAgeTest : StringSpec({
    val now = Instant.parse("2026-06-12T12:00:00Z")

    fun ageOf(secondsAgo: Long): String = relativeAge(now.minusSeconds(secondsAgo), now)

    "under a minute renders just now" {
        ageOf(0) shouldBe "just now"
        ageOf(59) shouldBe "just now"
    }

    "minutes branch from 60 s up to an hour" {
        ageOf(60) shouldBe "1 m ago"
        ageOf(3599) shouldBe "59 m ago"
    }

    "hours branch from 3600 s up to a day" {
        ageOf(3600) shouldBe "1 h ago"
        ageOf(2 * 3600) shouldBe "2 h ago"
        ageOf(86399) shouldBe "23 h ago"
    }

    "days branch from 86400 s" {
        ageOf(86400) shouldBe "1 d ago"
        ageOf(3 * 86400 + 120) shouldBe "3 d ago"
    }

    "clock skew (future timestamp) does not crash and renders just now" {
        relativeAge(now.plusSeconds(90), now) shouldBe "just now"
    }
})
