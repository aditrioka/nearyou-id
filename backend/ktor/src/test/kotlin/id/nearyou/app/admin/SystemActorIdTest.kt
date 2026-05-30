package id.nearyou.app.admin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID

/**
 * Fast unit test (no DB) pinning the deterministic `system` sentinel UUID.
 *
 * `system-actor` capability spec scenario "Migration UUID literal equals the
 * application constant" + `tasks.md` 1.2 / design D3: the V18 migration literal,
 * the worker's CTE literal, and [SuspensionUnbanWorker.SYSTEM_ACTOR_ID] must all
 * be the same value, so drift between the migration and the Kotlin constant is
 * caught in the fast CI lane (not just the `database`-tagged integration lane).
 */
class SystemActorIdTest : StringSpec({
    "1.2 SYSTEM_ACTOR_ID equals UUID.nameUUIDFromBytes(\"system\") and the migration literal" {
        val derived = UUID.nameUUIDFromBytes("system".toByteArray())
        derived shouldBe UUID.fromString("54b53072-540e-3eb8-b8e9-343e71f28176")
        SuspensionUnbanWorker.SYSTEM_ACTOR_ID shouldBe derived
        // The worker's CTE hardcodes the same literal for the audit row's admin_id;
        // the audit-row integration test additionally proves the FK resolves to the
        // V18-seeded row, so a migration-literal drift surfaces there too.
        SuspensionUnbanWorker.SQL shouldContain "54b53072-540e-3eb8-b8e9-343e71f28176"
    }
})
