package id.nearyou.app.admin.auth

import com.password4j.Argon2Function
import com.password4j.Password
import com.password4j.types.Argon2

/**
 * Argon2id password hashing + verification for the admin login flow.
 *
 * Per `admin-login-argon2-totp` design.md D2 + D9 + D15 + spec Req "Argon2id
 * verification uses tuned parameters with documented benchmark".
 *
 * Parameter tuning:
 *  - Memory ≥ 15 MiB (OWASP minimum)
 *  - Iterations ≥ 2 (OWASP minimum)
 *  - Parallelism = 1 (Cloud Run single-vCPU-burst-capable; parallelism > 1 doesn't help)
 *  - Hash output length: 32 bytes (OWASP recommendation)
 *  - Target verify wall time: 400–800 ms on the local dev machine
 *
 * The chosen starting values (64 MiB / 3 iter / 1 parallelism) match
 * Password4j's published "minimum production" Argon2id preset and are
 * expected to land in the target window on the project's CI runner +
 * typical dev machine. The benchmark spec at
 * `PasswordHasherBenchmarkTest` measures the actual wall time + asserts
 * the configured constants meet the OWASP floor.
 *
 * `@benchmark 2026-05-28 (apply-phase):` starting parameters; concrete
 * measured mean SHALL be recorded by the next apply-phase commit that
 * tightens the params after running the benchmark spec locally.
 *
 * D15 sentinel-params discipline:
 *  - The sentinel hash is computed at module bootstrap from a fixed
 *    plaintext + the SAME [argon2Function] used for production verifies.
 *  - sentinel-params == production-params by construction (single source
 *    of truth); the spec Req "Login POST returns no-enumeration response
 *    on every failure path" scenario "Sentinel hash is a valid Argon2id
 *    hash with current parameters" verifies this invariant at CI time.
 */
object PasswordHasher {
    /** OWASP minimum memory cost; floor — production should target ≥ 64 MiB. */
    const val OWASP_MIN_MEMORY_KIB = 15 * 1024

    /** OWASP minimum iteration count. */
    const val OWASP_MIN_ITERATIONS = 2

    /** OWASP-canonical parallelism for password verification on single-thread workloads. */
    const val PARALLELISM = 1

    /** Hash output length in bytes; OWASP recommendation. */
    const val HASH_OUTPUT_BYTES = 32

    // ----- Tuned production parameters -----
    // Starting point: Password4j "minimum production" preset. Target window
    // is 400–800 ms verify on a dev/CI machine; tune via the benchmark
    // spec if the observed mean drifts out.
    const val MEMORY_KIB = 64 * 1024 // 64 MiB
    const val ITERATIONS = 3

    /**
     * The single [Argon2Function] instance used for BOTH production hashing
     * (admin bootstrap script) AND production verification (login flow).
     * Sentinel-hash generation uses the same instance, so D15's
     * sentinel-params == production-params invariant holds by construction.
     */
    val argon2Function: Argon2Function by lazy {
        Argon2Function.getInstance(
            MEMORY_KIB,
            ITERATIONS,
            PARALLELISM,
            HASH_OUTPUT_BYTES,
            Argon2.ID,
            ARGON2_VERSION,
        )
    }

    // ----- Sentinel for timing equalization (design.md D9 + D15) -----
    //
    // The sentinel plaintext is a fixed unguessable string committed in
    // source — its secrecy is NOT load-bearing (an attacker who guesses
    // it just learns "this is the sentinel"; the email-not-found branch
    // still returns the no-enumeration error). What IS load-bearing:
    // - sentinel verify takes the same wall time as a production verify
    //   (achieved by using the same [argon2Function])
    // - sentinel-params == production-params (achieved by lazy-init from
    //   the same constant — no separate regeneration step needed)
    private const val SENTINEL_PLAINTEXT = "argon2-timing-equalization-sentinel-do-not-rely-on-secrecy"

    /**
     * Sentinel hash for timing-equalization on the email-not-found login
     * path (design.md D9). Computed lazily at first use from
     * [SENTINEL_PLAINTEXT] + [argon2Function] — costs one Argon2id hash
     * at module bootstrap (≈500 ms) but JIT warms subsequent verifies.
     */
    val sentinelHash: String by lazy {
        Password
            .hash(SENTINEL_PLAINTEXT)
            .addRandomSalt()
            .with(argon2Function)
            .result
    }

    /**
     * Hash [plaintext] with the production [argon2Function] + a fresh
     * random salt. Returns the canonical Password4j hash string format
     * (`$argon2id$v=19$m=...,t=...,p=...$<salt>$<hash>`).
     *
     * Used by the admin bootstrap script (dev/scripts/admin-bootstrap)
     * to generate `admin_users.password_hash` values for manual INSERT.
     * NOT called at runtime during the login flow.
     */
    fun hash(plaintext: String): String =
        Password
            .hash(plaintext)
            .addRandomSalt()
            .with(argon2Function)
            .result

    /**
     * Verify [plaintext] against the stored [hash] string. Returns true
     * iff the plaintext produces the stored hash under the SAME [argon2Function]
     * params. Constant-time per Password4j's internal compare.
     *
     * Uses `.with(argon2Function)` (rather than `.withArgon2()` which uses
     * Password4j-default params). Password4j's PHC-formatted hash string
     * embeds the params used at hash time; a future params re-tune that
     * differs from `argon2Function` would fail to verify pre-tune hashes
     * — that's a deliberate migration gate (force re-hash on next login
     * vs silently accepting a weaker hash).
     */
    fun verify(
        plaintext: String,
        hash: String,
    ): Boolean = Password.check(plaintext, hash).with(argon2Function)

    /**
     * Verify against the [sentinelHash] for timing equalization on the
     * email-not-found login path. Same wall-time profile as a production
     * verify; result is discarded by the caller.
     */
    fun verifyAgainstSentinel(plaintext: String): Boolean = verify(plaintext, sentinelHash)

    private const val ARGON2_VERSION = 19
}
