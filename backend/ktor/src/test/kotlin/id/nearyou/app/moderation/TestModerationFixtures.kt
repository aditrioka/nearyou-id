package id.nearyou.app.moderation

import id.nearyou.app.infra.repo.JdbcModerationQueueRepository
import id.nearyou.data.repository.ModerationQueueRepository
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared test helpers for the content-moderation integration. Existing tests that
 * pre-date `content-moderation-keyword-lists` use these to satisfy the new
 * `textModerator` + `moderationQueue` constructor parameters on
 * `CreatePostService` / `ReplyService` / `ChatService` without bloating each
 * test file with the same boilerplate.
 *
 * The Allow-only moderator returns `Verdict.Allow` for any input — the matcher
 * runs against empty lists so every match check is a structural no-op.
 */
object TestModerationFixtures {
    val ALLOW_ONLY_LOADER: ModerationListLoader =
        object : ModerationListLoader {
            override fun load(list: ModerationList): List<String> = emptyList()

            override fun loadThreshold(): Int = 3
        }

    val ALLOW_ONLY_MODERATOR: TextModerator = TextModerator(ALLOW_ONLY_LOADER)

    /**
     * Real DB-backed ModerationQueueRepository — safe to share across tests since
     * it has no per-instance state. Tests that need a fake (e.g., to spy on
     * `upsertUuIteKeywordMatchRow` calls) inject their own.
     */
    val SHARED_QUEUE_REPO: ModerationQueueRepository = JdbcModerationQueueRepository()
}

/**
 * Allow-only [TextModerator] that records how many times `moderate(...)` ran, so
 * tests can prove an earlier gate (rate-limit / block) short-circuited BEFORE the
 * moderator. [TextModerator] is final and `moderate` is not `open`, so the count
 * is captured by a recording [ModerationListLoader]: every `moderate(content)`
 * call begins with exactly one `load(ProfanityList)` lookup (see
 * `TextModerator.moderate`), giving a faithful per-invocation counter. The loader
 * returns empty lists, so the verdict is always `Verdict.Allow`.
 *
 * Used by `ReplyRateLimitTest` (rate-limited reply) + `ChatFoundationRouteTest`
 * (blocked chat send) — the earlier `reply-rate-limit-moderator-spy` +
 * `chat-block-check-moderator-spy` follow-ups.
 */
class RecordingTextModerator {
    val moderateCallCount: AtomicInteger = AtomicInteger(0)

    private val loader: ModerationListLoader =
        object : ModerationListLoader {
            override fun load(list: ModerationList): List<String> {
                // ProfanityList is always the first list `moderate` consults — count
                // it once per invocation. UuIteList loads (Allow path) do not increment.
                if (list == ModerationList.ProfanityList) moderateCallCount.incrementAndGet()
                return emptyList()
            }

            override fun loadThreshold(): Int = 3
        }

    val moderator: TextModerator = TextModerator(loader)
}
