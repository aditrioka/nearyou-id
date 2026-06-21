package id.nearyou.app.infra.r2

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.time.Duration.Companion.hours

/**
 * Unit coverage for the object-storage spec scenarios that need no network.
 *
 * Covered here:
 * - "App boots with R2 unconfigured" / "No-op store degrades gracefully" — the factory
 *   binds [NoOpObjectStore] when config is null/incomplete; its `put`/`delete` no-op
 *   (no throw) and `presignedGetUrl` raises the defined [ObjectStoreUnconfiguredException]
 *   (a defined "unconfigured" outcome, not an unhandled crash).
 * - "Credentials resolved via the secret helper" — [R2Config.fromSecrets] reads every
 *   credential through the injected `resolveSecret` lambda using the UNPREFIXED base slot
 *   names (the env-specific Secret Manager slot is wired by the deploy `--set-secrets`,
 *   not composed in code); unset slots → blank → `isComplete()` false → no-op binding.
 *
 * Deferred to the parent's integration tests (need a local S3 mock — e.g. LocalStack /
 * MinIO / s3mock — exercising a real [R2ObjectStore] against an S3 endpoint), since they
 * require an actual signing + HTTP round-trip the AWS SDK performs against a live bucket:
 * - "Stored object is retrievable via a signed URL within its TTL" (put → presign → GET).
 * - "Signed URL stops working after its TTL" (presign with a short TTL → GET after expiry
 *   is rejected). R2 honors a 1s–7days expiry; the SDK encodes it into the SigV4 signature.
 * The "No vendor SDK type leaks outside the module" scenario is enforced by the
 * `VendorSdkLeakageScanTest` detekt scan, not a unit test here.
 */
class R2ObjectStoreTest : StringSpec({

    val completeConfig =
        R2Config(
            accountId = "acct123",
            accessKeyId = "ak-123",
            secretAccessKey = "sk-456",
            bucket = "exports",
        )

    // --- fail-soft / NoOp ---

    "factory returns NoOp when config is null" {
        objectStore(null).isConfigured().shouldBeFalse()
    }

    "factory returns NoOp when config is incomplete (blank secret access key)" {
        objectStore(completeConfig.copy(secretAccessKey = "")).isConfigured().shouldBeFalse()
    }

    "factory returns NoOp when config is incomplete (blank bucket)" {
        objectStore(completeConfig.copy(bucket = "")).isConfigured().shouldBeFalse()
    }

    "factory returns the R2 store when config is complete" {
        objectStore(completeConfig).isConfigured().shouldBeTrue()
    }

    "NoOp store reports not-configured" {
        NoOpObjectStore.isConfigured().shouldBeFalse()
    }

    "NoOp put and delete are no-ops (do not throw)" {
        shouldNotThrowAny {
            NoOpObjectStore.put("k", byteArrayOf(1, 2, 3), "application/zip")
            NoOpObjectStore.delete("k")
        }
    }

    "NoOp presignedGetUrl throws ObjectStoreUnconfiguredException (defined unconfigured outcome)" {
        shouldThrow<ObjectStoreUnconfiguredException> {
            NoOpObjectStore.presignedGetUrl("k", 24.hours)
        }
    }

    "factory binds the same NoOp singleton instance when unconfigured" {
        objectStore(null).shouldBeSameInstanceAs(NoOpObjectStore)
    }

    // --- config resolution via the secret helper ---

    "fromSecrets reads every credential through the resolver using the UNPREFIXED base slot names" {
        val seen = mutableListOf<String>()
        val resolver: (String) -> String? = { slot ->
            seen += slot
            mapOf(
                "r2-account-id" to "acct",
                "r2-access-key-id" to "ak",
                "r2-secret-access-key" to "sk",
                "r2-export-bucket" to "bkt",
            )[slot]
        }

        val config = R2Config.fromSecrets(resolver)

        config shouldBe R2Config(accountId = "acct", accessKeyId = "ak", secretAccessKey = "sk", bucket = "bkt")
        config.isComplete().shouldBeTrue()
        // Resolves the UNPREFIXED names — the env-specific Secret Manager slot (`staging-…`) is
        // mapped onto these env vars by the deploy `--set-secrets`, NOT composed in code.
        seen shouldBe listOf("r2-account-id", "r2-access-key-id", "r2-secret-access-key", "r2-export-bucket")
    }

    "fromSecrets yields an incomplete config when slots are unset (fail-soft path)" {
        val config = R2Config.fromSecrets { null }
        config.isComplete().shouldBeFalse()
        objectStore(config).isConfigured().shouldBeFalse()
    }
})
