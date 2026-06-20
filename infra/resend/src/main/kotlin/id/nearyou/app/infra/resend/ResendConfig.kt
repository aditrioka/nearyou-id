package id.nearyou.app.infra.resend

/**
 * Resend config. [apiKey] is secret (slot `resend-api-key`, resolved via the
 * `secretKey(env, name)` convention — see [resendApiKeySlot]); [fromAddress] is the
 * non-sensitive verified sender address (dashboard-visible, like slot names per the
 * public-repo posture).
 *
 * [isComplete] gates the fail-soft factory: a blank key/from → [NoOpEmailSender].
 */
data class ResendConfig(
    val apiKey: String,
    val fromAddress: String = DEFAULT_FROM_ADDRESS,
) {
    fun isComplete(): Boolean = apiKey.isNotBlank() && fromAddress.isNotBlank()

    companion object {
        /**
         * Default verified sender for nearyou-id transactional mail. Non-sensitive
         * (a public from-address); overridable via the constructor when a different
         * verified domain is wired. `noreply@` because these are no-reply system mails.
         */
        const val DEFAULT_FROM_ADDRESS: String = "NearYou <noreply@nearyou.id>"
    }
}

/**
 * The env-namespaced Secret Manager slot for the Resend API key, mirroring
 * `secretKey(env, "resend-api-key")`: `staging-resend-api-key` in staging, `resend-api-key`
 * otherwise. Derived locally so `:infra:resend` does NOT depend on the `secretKey()` helper
 * in `:backend:ktor` (which would be a circular dependency — the `:infra:redis`
 * `redisUrlSlot` precedent).
 */
internal fun resendApiKeySlot(env: String): String = if (env == "staging") "staging-resend-api-key" else "resend-api-key"
