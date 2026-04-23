package id.nearyou.data.repository

import java.util.UUID

/**
 * Delivery-channel seam for notifications.
 *
 * V10 ships the in-app channel only (the DB row is the source of truth per
 * docs/05-Implementation.md:849). The FCM change (Phase 2 item 7) will add
 * its own implementation in `:infra:firebase` without touching
 * `NotificationEmitter` or any of the four emit-site services.
 *
 * Interface lives in `:core:data` (not `:backend:ktor`) so infra modules
 * (e.g. future `:infra:firebase`) can implement it without pulling a runtime
 * dependency on the Ktor backend module — the dependency arrow points
 * `:backend:ktor → :core:data → (no one)`. Placing the interface in
 * `:core:data` keeps that arrow unidirectional.
 */
fun interface NotificationDispatcher {
    fun dispatch(notificationId: UUID)
}
