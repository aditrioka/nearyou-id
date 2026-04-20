package id.nearyou.app.auth.session

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class RefreshRotationTest : StringSpec({
    "happy rotation produces a new token in the same family and marks the old as used" {
        val tokens = InMemoryRefreshTokens()
        val user = userRow()
        val users = InMemoryUsers(listOf(user))
        var now = Instant.parse("2026-04-20T10:00:00Z")
        val service = RefreshTokenService(tokens, users, nowProvider = { now })

        val first = service.issue(user.id, deviceFingerprintHash = null)
        now = now.plusSeconds(10)
        val second = service.rotate(first.rawToken, deviceFingerprintHash = null)

        second.row.familyId shouldBe first.row.familyId
        tokens.rows[first.row.id]!!.usedAt shouldBe now.minusSeconds(0)
        tokens.rows[second.row.id]!!.usedAt shouldBe null
    }

    "re-presenting the original token within 30 seconds of first use succeeds (idempotent overlap)" {
        val tokens = InMemoryRefreshTokens()
        val user = userRow()
        val users = InMemoryUsers(listOf(user))
        var now = Instant.parse("2026-04-20T10:00:00Z")
        val service = RefreshTokenService(tokens, users, nowProvider = { now })

        val first = service.issue(user.id, deviceFingerprintHash = null)
        now = now.plusSeconds(5)
        service.rotate(first.rawToken, deviceFingerprintHash = null)

        // Same client retries within the overlap window — must succeed without revocation.
        now = now.plusSeconds(10) // 15 s after first use, still inside 30 s overlap
        val retry = service.rotate(first.rawToken, deviceFingerprintHash = null)

        retry.row.familyId shouldBe first.row.familyId
        users.rows[user.id]!!.tokenVersion shouldBe 0
    }

    "re-presenting the original token after the overlap revokes the family and bumps token_version" {
        val tokens = InMemoryRefreshTokens()
        val user = userRow()
        val users = InMemoryUsers(listOf(user))
        var now = Instant.parse("2026-04-20T10:00:00Z")
        val service = RefreshTokenService(tokens, users, nowProvider = { now })

        val first = service.issue(user.id, deviceFingerprintHash = null)
        now = now.plusSeconds(1)
        val second = service.rotate(first.rawToken, deviceFingerprintHash = null)

        // 60 s after first use, well beyond 30 s overlap.
        now = now.plusSeconds(60)
        val ex =
            shouldThrow<TokenReuseException> {
                service.rotate(first.rawToken, deviceFingerprintHash = null)
            }
        ex.ownerUserId shouldBe user.id

        users.rows[user.id]!!.tokenVersion shouldBe 1
        tokens.rows[first.row.id]!!.revokedAt shouldBe now
        tokens.rows[second.row.id]!!.revokedAt shouldBe now
    }

    "expired token rejected" {
        val tokens = InMemoryRefreshTokens()
        val user = userRow()
        val users = InMemoryUsers(listOf(user))
        var now = Instant.parse("2026-04-20T10:00:00Z")
        val service = RefreshTokenService(tokens, users, nowProvider = { now })

        val issued = service.issue(user.id, deviceFingerprintHash = null)
        now = now.plus(31, java.time.temporal.ChronoUnit.DAYS)

        shouldThrow<RefreshTokenInvalidException> {
            service.rotate(issued.rawToken, deviceFingerprintHash = null)
        }
    }

    "unknown token rejected" {
        val tokens = InMemoryRefreshTokens()
        val users = InMemoryUsers()
        val service = RefreshTokenService(tokens, users)

        shouldThrow<RefreshTokenInvalidException> {
            service.rotate("not-a-real-token", deviceFingerprintHash = null)
        }
    }

    "deleteAllForUser removes all rows for that user" {
        val tokens = InMemoryRefreshTokens()
        val user = userRow()
        val other = userRow()
        val users = InMemoryUsers(listOf(user, other))
        val service = RefreshTokenService(tokens, users)

        service.issue(user.id, null)
        service.issue(user.id, null)
        service.issue(other.id, null)

        val deleted = tokens.deleteAllForUser(user.id)
        deleted shouldBe 2
        tokens.rows.values.count { it.userId == user.id } shouldBe 0
        tokens.rows.values.count { it.userId == other.id } shouldBe 1
    }
})
