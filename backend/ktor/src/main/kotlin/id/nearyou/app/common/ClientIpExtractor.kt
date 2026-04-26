package id.nearyou.app.common

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.plugins.origin
import io.ktor.util.AttributeKey

/**
 * Populates `call.clientIp` (`AttributeKey<String>("ClientIp")`) on every request
 * via the canonical precedence ladder from
 * [`docs/05-Implementation.md`](../../../../../../../../docs/05-Implementation.md)
 * § Cloudflare-Fronted IP Extraction:
 *
 *  1. `CF-Connecting-IP` header — Cloudflare origin-pull path (production + staging trustworthy).
 *  2. First comma-separated entry in `X-Forwarded-For` — local-dev / staging fallback when CF is absent.
 *  3. `call.request.origin.remoteHost` — last resort (raw socket address).
 *  4. Literal `"unknown"` — deterministic bucket key when all sources are empty.
 *
 * The value is set once per request under the [ClientIpAttributeKey]; idempotent if
 * the plugin is invoked twice (the second call observes the existing attribute and
 * returns).
 *
 * ## Spoof Protection
 *
 * The extractor itself does NOT verify that the inbound request actually transited
 * the Cloudflare edge — that defense lives at infrastructure level:
 *  - **Cloud-Armor-wired**: Cloud Run ingress allow-only-CF + Cloud Armor IP allowlist
 *    of Cloudflare ranges (the canonical production posture).
 *  - **Phase 1 MVP** (no Cloud Armor yet): rely on Cloud Run ingress
 *    `internal-and-cloud-load-balancing` + Cloudflare DNS as the practical control.
 *
 * Without one of these defenses, a direct hit to `*.run.app` could spoof
 * `CF-Connecting-IP`. The lint rule [`RawXForwardedForRule`] forbids direct reads of
 * `X-Forwarded-For` outside this file so the precedence ladder remains the single
 * sanctioned consumer.
 *
 * ## Install order
 *
 * Install BEFORE authentication, rate-limiting, and any business handler — see
 * `Application.module()` ordering. Down-stream consumers read `call.clientIp` via
 * the typed extension on this file.
 */
val ClientIpAttributeKey: AttributeKey<String> = AttributeKey("ClientIp")

const val UNKNOWN_CLIENT_IP: String = "unknown"

/**
 * Ktor application plugin that resolves the client IP and sets [ClientIpAttributeKey]
 * on every request. The plugin is idempotent — re-installing or re-invoking does not
 * overwrite a value that's already been set on the call.
 */
val ClientIpExtractorPlugin =
    createApplicationPlugin(name = "ClientIpExtractor") {
        on(CallSetup) { call ->
            if (call.attributes.contains(ClientIpAttributeKey)) return@on
            val resolved = resolveClientIp(call)
            call.attributes.put(ClientIpAttributeKey, resolved)
        }
    }

/**
 * Read-only typed accessor so call-sites use `call.clientIp` rather than raw
 * `call.attributes[ClientIpAttributeKey]`. Returns the resolved value, falling back
 * to [UNKNOWN_CLIENT_IP] if the plugin somehow did not run (defensive — should never
 * happen in practice when the plugin is installed at the application level).
 */
val ApplicationCall.clientIp: String
    get() = attributes.getOrNull(ClientIpAttributeKey) ?: resolveClientIp(this).also {
        attributes.put(ClientIpAttributeKey, it)
    }

internal fun resolveClientIp(call: ApplicationCall): String {
    // 1. CF-Connecting-IP — canonical when present.
    cfConnectingIp(call)?.let { return it }
    // 2. First entry in X-Forwarded-For — local dev / staging fallback only.
    xForwardedForFirst(call)?.let { return it }
    // 3. remoteHost — raw socket address.
    val remote = call.request.origin.remoteHost.trim()
    if (remote.isNotEmpty()) return remote
    // 4. Deterministic fallback string.
    return UNKNOWN_CLIENT_IP
}

private fun cfConnectingIp(call: ApplicationCall): String? {
    val raw = call.request.headers["CF-Connecting-IP"] ?: return null
    val trimmed = raw.trim()
    return trimmed.takeIf { it.isNotEmpty() }
}

// `X-Forwarded-For` is read here only — direct reads elsewhere are forbidden by
// the `RawXForwardedForRule` Detekt rule. This file is the single sanctioned
// consumer.
private fun xForwardedForFirst(call: ApplicationCall): String? {
    val raw = call.request.headers["X-Forwarded-For"] ?: return null
    return raw
        .split(',')
        .firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
