package id.nearyou.app.admin.auth

import io.kotest.core.NamedTag
import io.kotest.core.spec.style.StringSpec
import kotlin.time.measureTime

/**
 * Benchmark spec for [PasswordHasher] — every test tagged `benchmark` so
 * PR-time CI skips them (`./gradlew test -Dkotest.tags=!benchmark` is the
 * project's PR-time invocation per the precedent set by
 * `admin-login-argon2-totp` design.md D2 + tasks.md 11.5).
 *
 * The spec runs the production Argon2id verify 10× against a freshly-
 * hashed value and asserts the mean wall time is in the expected window.
 * Bounds are intentionally loose (300–2000 ms) to tolerate JIT warmup +
 * CI-machine variance; the target is 400–800 ms but the assertion only
 * fails on catastrophic drift (e.g. params accidentally tuned 10× too
 * low → 50 ms, or accidentally 10× too high → 8 s).
 *
 * Run locally during apply-phase tuning:
 *   ./gradlew :backend:ktor:test -Dkotest.tags=benchmark \
 *     --tests 'id.nearyou.app.admin.auth.PasswordHasherBenchmarkTest'
 *
 * Mean / median observed at apply time SHOULD land in the
 * `PasswordHasher.kt` `@benchmark` comment.
 */
class PasswordHasherBenchmarkTest : StringSpec({

    val benchmarkTag = setOf(NamedTag("benchmark"))

    "Argon2id verify mean wall time within [300, 2000] ms (n=10)".config(tags = benchmarkTag) {
        val hash = PasswordHasher.hash("benchmark-fixture-plaintext")

        // One warmup verify to amortize JIT.
        PasswordHasher.verify("benchmark-fixture-plaintext", hash)

        val durations =
            (1..10).map {
                measureTime {
                    PasswordHasher.verify("benchmark-fixture-plaintext", hash)
                }.inWholeMilliseconds
            }
        val mean = durations.average()

        check(mean in 300.0..2000.0) {
            "Argon2id verify mean wall time $mean ms out of [300, 2000] window. " +
                "Durations: $durations. " +
                "Update PasswordHasher.MEMORY_KIB / ITERATIONS if drifting."
        }
    }
})
