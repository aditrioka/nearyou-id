package id.nearyou.app.infra.resend

/**
 * Resend config. [apiKey] is secret — resolved by the UNPREFIXED logical name
 * `resend-api-key` (env var `RESEND_API_KEY`). The env-specific Secret Manager slot
 * (`staging-resend-api-key`) is mapped onto that env var by the deploy
 * (`deploy-staging.yml --set-secrets="RESEND_API_KEY=staging-resend-api-key:latest"`),
 * so code resolution is env-agnostic — mirroring `secrets.resolve("redis-url")` /
 * RevenueCat `secrets.resolve(SECRET_BEARER)`, NOT `secrets.resolve(secretKey(env, …))`.
 * [fromAddress] is the non-sensitive verified sender address (dashboard-visible, like
 * slot names per the public-repo posture).
 *
 * [recipientOverride], when non-null, replaces every `to` at send time — the STAGING
 * recipient guard (docs/10 §3.9): staging code MUST override the recipient to a test
 * inbox so a stale/synthetic user row can never cause a real address to be emailed.
 * `null` in production (send to the real recipient).
 *
 * [isComplete] gates the fail-soft factory: a blank key/from → [NoOpEmailSender].
 */
data class ResendConfig(
    val apiKey: String,
    val fromAddress: String = DEFAULT_FROM_ADDRESS,
    val recipientOverride: String? = null,
) {
    fun isComplete(): Boolean = apiKey.isNotBlank() && fromAddress.isNotBlank()

    companion object {
        /**
         * Default verified sender for nearyou-id transactional mail. Non-sensitive
         * (a public from-address); overridable via the constructor when a different
         * verified domain is wired. `noreply@` because these are no-reply system mails.
         * Domain is `send.nearyou.id` — the verified Resend sending subdomain (docs/10
         * §3.9; the root `nearyou.id` is deliberately NOT a verified sender, so a from of
         * `@nearyou.id` would be rejected by Resend).
         */
        const val DEFAULT_FROM_ADDRESS: String = "NearYou <noreply@send.nearyou.id>"

        /** The UNPREFIXED logical name passed to `resolveSecret` (env var `RESEND_API_KEY`). */
        const val SLOT_API_KEY: String = "resend-api-key"
    }
}
