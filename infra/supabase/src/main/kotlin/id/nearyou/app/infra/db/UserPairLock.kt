package id.nearyou.app.infra.db

import java.sql.Connection
import java.util.UUID

/**
 * Transaction-scoped advisory lock on a canonical-ordered user pair —
 * `hashtext(LEAST(a,b) || ':' || GREATEST(a,b))` — released automatically at
 * COMMIT/ROLLBACK (`pg_advisory_xact_lock`).
 *
 * Serializes every check-then-act sequence that touches the same two users so
 * interleavings can't commit contradictory edges. Call sites (rule-of-three
 * extraction, 2026-06-10 audit): chat conversation-slot creation
 * (`ChatRepository`), follow-vs-block race (`JdbcUserFollowsRepository.followInTx`
 * SELECT-blocks-then-INSERT-follow), block-removes-follows
 * (`JdbcUserBlockRepository.create` INSERT-block + DELETE-follows) — without the
 * lock a concurrent follow and block can fully interleave under READ COMMITTED,
 * committing a `follows` row alongside a `user_blocks` row; the stale edge then
 * silently RESURRECTS on unblock (violates docs/02 §4 "follow relationships are
 * automatically removed when a block is applied").
 *
 * MUST be called inside an open transaction (`autoCommit = false`) — xact locks
 * outside a transaction release immediately and protect nothing.
 */
object UserPairLock {
    fun acquire(
        conn: Connection,
        a: UUID,
        b: UUID,
    ) {
        conn.prepareStatement(
            "SELECT pg_advisory_xact_lock(hashtext(LEAST(?::text, ?::text) || ':' || GREATEST(?::text, ?::text)))",
        ).use { ps ->
            ps.setObject(1, a)
            ps.setObject(2, b)
            ps.setObject(3, a)
            ps.setObject(4, b)
            ps.executeQuery().use { it.next() }
        }
    }
}
