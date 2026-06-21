package id.nearyou.app.infra.r2

/**
 * Cloudflare R2 connection config. All four fields are secret material resolved via the
 * `:backend:ktor` `SecretResolver` chain (Secret Manager in staging/prod). None is logged.
 *
 * Slot names (design D6 / migration plan step 3):
 * `r2-account-id`, `r2-access-key-id`, `r2-secret-access-key`, `r2-export-bucket`.
 *
 * These are the UNPREFIXED logical names passed to `resolveSecret`. The env-namespaced
 * Secret Manager slot (`staging-<name>` in staging) is selected by the **deploy** —
 * `deploy-staging.yml --set-secrets="R2_ACCOUNT_ID=staging-r2-account-id:latest,…"` maps
 * the staging slot onto the unprefixed env var that [EnvVarSecretResolver] reads
 * (`r2-account-id` → `R2_ACCOUNT_ID`). Resolution in code is env-agnostic; mirrors the
 * canonical `secrets.resolve("redis-url")` / RevenueCat `secrets.resolve(SECRET_BEARER)`
 * convention (NOT `secrets.resolve(secretKey(env, …))`).
 *
 * [isComplete] is the blank-aware guard the Koin factory uses to fall back to
 * [NoOpObjectStore] when any credential is unset (dev/test/un-provisioned staging).
 */
data class R2Config(
    val accountId: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val bucket: String,
) {
    fun isComplete(): Boolean =
        accountId.isNotBlank() && accessKeyId.isNotBlank() &&
            secretAccessKey.isNotBlank() && bucket.isNotBlank()

    companion object {
        /** Secret slot base names — the UNPREFIXED logical names passed to `resolveSecret`. */
        const val SLOT_ACCOUNT_ID: String = "r2-account-id"
        const val SLOT_ACCESS_KEY_ID: String = "r2-access-key-id"
        const val SLOT_SECRET_ACCESS_KEY: String = "r2-secret-access-key"
        const val SLOT_BUCKET: String = "r2-export-bucket"

        /**
         * Builds an [R2Config] by resolving each slot through [resolveSecret] using the
         * UNPREFIXED base names. The env-specific Secret Manager slot is wired by the deploy
         * (`--set-secrets`), NOT composed here — so this module never needs `env` nor the
         * `secretKey()` helper from `:backend:ktor` (which would be a circular dependency).
         * Unresolved slots become blank, so [isComplete] is false and the factory binds the
         * fail-soft no-op.
         *
         * [resolveSecret] is the secret-resolution lambda; production passes
         * `secrets::resolve` from the `:backend:ktor` `SecretResolver`. Tests pass a fake.
         */
        fun fromSecrets(resolveSecret: (String) -> String?): R2Config =
            R2Config(
                accountId = resolveSecret(SLOT_ACCOUNT_ID).orEmpty(),
                accessKeyId = resolveSecret(SLOT_ACCESS_KEY_ID).orEmpty(),
                secretAccessKey = resolveSecret(SLOT_SECRET_ACCESS_KEY).orEmpty(),
                bucket = resolveSecret(SLOT_BUCKET).orEmpty(),
            )
    }
}
