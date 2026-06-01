package id.nearyou.app.timeline

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Supplies a stable-per-process session id, sent as the `X-Session-Id` header on every Nearby fetch
 * so the backend's per-session soft-cap accounting (50 posts/session) actually engages rather than
 * collapsing every read into the shared `no-session` bucket.
 *
 * CRITICAL (design D5 / spec § "SessionIdProvider yields a stable per-process session id"): the id is
 * computed EXACTLY ONCE and captured in [sessionId] at construction — NOT recomputed per access.
 * Registering this as a Koin `single` makes the *provider* a singleton, but the VALUE must be
 * field-captured: a fresh `Uuid.random()` per call would open a new per-session Redis bucket on every
 * request, so the soft cap would never engage. A fresh id per process launch is acceptable (sessions
 * are an hourly Redis bucket; a new launch simply starts a fresh soft-cap window).
 *
 * The `Uuid.random().toString()` hex+hyphen form (36 chars) matches the backend's accepted
 * `^[A-Za-z0-9-]{1,64}$` shape.
 */
@OptIn(ExperimentalUuidApi::class)
class SessionIdProvider {
    val sessionId: String = Uuid.random().toString()
}
