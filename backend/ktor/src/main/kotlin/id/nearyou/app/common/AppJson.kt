package id.nearyou.app.common

import kotlinx.serialization.json.Json

/**
 * THE shared server `Json` instance (docs/11 §3.3: "ONE shared Json instance" —
 * `ignoreUnknownKeys = true`, `explicitNulls = false`, `encodeDefaults = false`
 * is the kotlinx default, never `isLenient`). Consumed by the ContentNegotiation
 * install and any route-level decode that wants wire semantics identical to the
 * negotiated surface (2026-06-10 audit, finding 01-#14 — settings had started to
 * drift across 7+ scattered `Json { ... }` constructions, one of them rebuilt
 * per request inside the Apple S2S handler).
 *
 * Deliberately NOT used by the cursor codecs (`common/Cursor`, `chat/ChatCursors`)
 * — their strict-decode posture (malformed cursor → 400) is a different contract
 * from the tolerant wire surface.
 */
val AppJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
