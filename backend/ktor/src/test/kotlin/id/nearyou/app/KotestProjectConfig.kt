package id.nearyou.app

import io.kotest.core.config.AbstractProjectConfig
import org.flywaydb.core.Flyway
import java.sql.DriverManager

/**
 * Runs Flyway V1..V9 migrations once, at the start of the test JVM, if a
 * reachable Postgres is available on `DB_URL` (falling back to the local dev
 * compose default).
 *
 * Rationale: individual integration tests (V7 `LikeEndpointsTest`, V5
 * `BlockEndpointsTest`, V3 `SignupFlowTest`, etc.) assume a previously-migrated
 * schema — they directly `INSERT INTO users ...` without running Flyway
 * themselves. Locally this works because the dev docker-compose Postgres
 * volume is persistent across test runs. In CI with a fresh service container,
 * nothing has ever run Flyway, so those `INSERT`s blow up with
 * `relation "users" does not exist`.
 *
 * `MigrationV*SmokeTest` specs DO call `Flyway.migrate()` at spec init, but
 * that only helps if they happen to run first — Kotest does not guarantee
 * cross-spec ordering.
 *
 * This config centralizes bootstrap so every DB-tagged spec gets a migrated
 * schema regardless of discovery order. Flyway migrations are idempotent, so
 * re-running on a already-migrated dev DB is a no-op (confirms the existing
 * smoke test "re-running flywayMigrate is a no-op" assertions).
 *
 * If the DB is unreachable (local unit-only run without compose), we skip
 * silently — unit tests that don't touch the DB still run; DB-tagged specs
 * fail loudly on first connection attempt, as they would have anyway.
 */
@Suppress("unused")
class KotestProjectConfig : AbstractProjectConfig() {
    override suspend fun beforeProject() {
        val url = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5433/nearyou_dev"
        val user = System.getenv("DB_USER") ?: "postgres"
        val password = System.getenv("DB_PASSWORD") ?: "postgres"

        // Probe: if no Postgres is reachable, skip bootstrap entirely so unit-
        // only test runs don't fail at project startup.
        try {
            DriverManager.getConnection(url, user, password).use { it.isValid(2) }
        } catch (_: Exception) {
            return
        }

        Flyway
            .configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}
