package id.nearyou.data.repository

import java.util.UUID

/**
 * Resolve `actor_user_id → username` for notification body composition.
 *
 * The production impl reads from the `visible_users` view (NOT raw `users`)
 * so shadow-banned and hard-deleted actors return `null` — consumers
 * downstream (`PushCopy.bodyFor(notification, actorUsername=null)`) render
 * the actor-less fallback string. See
 * `openspec/changes/fcm-push-dispatch/design.md` D13 for the privacy
 * rationale.
 */
fun interface ActorUsernameLookup {
    /**
     * Returns the actor's username, or `null` when:
     *   - [actorUserId] is `null` (system-emitted notification), OR
     *   - the user is shadow-banned (`is_shadow_banned = TRUE`), OR
     *   - the user is hard-deleted (`deleted_at IS NOT NULL`), OR
     *   - the row does not exist.
     *
     * MUST NOT throw on missing or banned actors — the dispatch path
     * tolerates a null actor and falls back to the actor-less push copy.
     */
    fun lookup(actorUserId: UUID?): String?
}
