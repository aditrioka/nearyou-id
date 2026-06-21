package id.nearyou.app.infra.resend

import io.ktor.client.engine.HttpClientEngine
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Factory. Returns the real Resend-backed sender when [config] is complete, else the fail-soft
 * [NoOpEmailSender]. [engine] is injectable for tests (MockEngine); production passes null.
 * Mirrors the `:infra:cloudflare-images` `imageStore(...)` factory.
 */
fun emailSender(
    config: ResendConfig?,
    engine: HttpClientEngine? = null,
): EmailSender =
    if (config == null || !config.isComplete()) {
        NoOpEmailSender
    } else {
        ResendEmailSender(config, engine)
    }

/**
 * Koin module exposing a singleton [EmailSender].
 *
 * Resolves the UNPREFIXED logical name [ResendConfig.SLOT_API_KEY] (`resend-api-key`, env var
 * `RESEND_API_KEY`); the env-specific Secret Manager slot is mapped onto that env var by the
 * deploy (`--set-secrets`), so this module needs neither `env` nor the `secretKey()` helper in
 * `:backend:ktor` (which would be a circular dependency). The convention mirrors the canonical
 * `secrets.resolve("redis-url")` / RevenueCat `secrets.resolve(SECRET_BEARER)`.
 *
 * [resolveSecret] is the secret-resolution lambda; in production it's `secrets::resolve` from the
 * `:backend:ktor` `SecretResolver` chain. In tests it can be a fake closure. When the slot is
 * unset/blank the factory binds [NoOpEmailSender] (boot succeeds; sends no-op).
 *
 * Production wiring lives in `Application.kt`; this module is a separate, focused binding so the
 * `:infra:resend` boundary stays clean.
 */
fun emailSenderModule(resolveSecret: (String) -> String?): Module =
    module {
        single<EmailSender> {
            val apiKey = resolveSecret(ResendConfig.SLOT_API_KEY).orEmpty()
            emailSender(ResendConfig(apiKey = apiKey))
        }
    }
