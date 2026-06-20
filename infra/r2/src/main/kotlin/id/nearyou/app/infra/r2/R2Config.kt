package id.nearyou.app.infra.r2

/**
 * Cloudflare R2 connection config. All four fields are secret material resolved via the
 * `:backend:ktor` `SecretResolver` chain (Secret Manager in staging/prod). None is logged.
 *
 * Slot names (design D6 / migration plan step 3):
 * `r2-account-id`, `r2-access-key-id`, `r2-secret-access-key`, `r2-export-bucket`.
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
        /** Secret slot base names — mirror `secretKey(env, name)` (see [r2SecretSlot]). */
        const val SLOT_ACCOUNT_ID: String = "r2-account-id"
        const val SLOT_ACCESS_KEY_ID: String = "r2-access-key-id"
        const val SLOT_SECRET_ACCESS_KEY: String = "r2-secret-access-key"
        const val SLOT_BUCKET: String = "r2-export-bucket"

        /**
         * Builds an [R2Config] by resolving each slot through [resolveSecret].
         *
         * [env] selects the slot prefix locally — mirroring `secretKey(env, name)`
         * (`staging-<name>` in staging, `<name>` otherwise) — so this module does NOT
         * import the `secretKey()` helper from `:backend:ktor` (a circular dependency;
         * the `:infra:redis` `redisUrlSlot` precedent). Unresolved slots become blank,
         * so [isComplete] is false and the factory binds the fail-soft no-op.
         *
         * [resolveSecret] is the secret-resolution lambda; production passes
         * `secrets::resolve` from the `:backend:ktor` `SecretResolver`. Tests pass a fake.
         */
        fun fromSecrets(
            env: String,
            resolveSecret: (String) -> String?,
        ): R2Config =
            R2Config(
                accountId = resolveSecret(r2SecretSlot(env, SLOT_ACCOUNT_ID)).orEmpty(),
                accessKeyId = resolveSecret(r2SecretSlot(env, SLOT_ACCESS_KEY_ID)).orEmpty(),
                secretAccessKey = resolveSecret(r2SecretSlot(env, SLOT_SECRET_ACCESS_KEY)).orEmpty(),
                bucket = resolveSecret(r2SecretSlot(env, SLOT_BUCKET)).orEmpty(),
            )
    }
}

/**
 * Env-namespaced slot name — `staging-<name>` in staging, `<name>` otherwise. Mirrors
 * `secretKey(env, name)` without importing it from `:backend:ktor` (avoids the circular
 * dependency; the `:infra:redis` `redisUrlSlot` precedent).
 */
internal fun r2SecretSlot(
    env: String,
    name: String,
): String = if (env == "staging") "staging-$name" else name
