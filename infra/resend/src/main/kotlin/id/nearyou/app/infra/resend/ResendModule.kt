package id.nearyou.app.infra.resend

import io.ktor.client.engine.HttpClientEngine

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
