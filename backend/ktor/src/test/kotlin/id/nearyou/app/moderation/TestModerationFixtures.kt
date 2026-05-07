package id.nearyou.app.moderation

import id.nearyou.app.infra.repo.JdbcModerationQueueRepository
import id.nearyou.data.repository.ModerationQueueRepository

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
