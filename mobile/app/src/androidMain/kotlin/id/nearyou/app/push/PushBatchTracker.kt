package id.nearyou.app.push

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

// Persisted (NOT in-memory) because onMessageReceived may cold-start the service per message —
// design D5: an in-memory window would reset on every cold start and never merge.
private val Context.pushBatchStore: DataStore<Preferences> by preferencesDataStore(
    name = "nearyou_push_batching",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Per-conversation display-side batching state (`mobile-push-message-handling` § "Incoming chat
 * pushes are batched per conversation"): a persisted last-push timestamp + in-window count keyed by
 * `conversation_id`. Within [WINDOW_MS] of the last push the count grows (the caller REPLACES the
 * tagged notification with the merged form, no fresh alert sound); outside it the count resets to 1
 * (fresh notification). The `conversation_id` key is opaque route payload — never rendered or
 * logged.
 */
// ponytail: per-conversation keys accrete in the prefs file; a retention sweep can prune them if the
// file ever measurably grows (each entry is ~a timestamp + an int).
class PushBatchTracker(private val context: Context) {
    data class Batch(val count: Int, val withinWindow: Boolean)

    suspend fun recordChatPush(
        conversationId: String,
        nowMs: Long,
    ): Batch {
        val tsKey = longPreferencesKey("ts:$conversationId")
        val countKey = intPreferencesKey("count:$conversationId")
        val prefs = context.pushBatchStore.data.first()
        val lastTs = prefs[tsKey]
        val withinWindow = lastTs != null && nowMs - lastTs <= WINDOW_MS
        val count = if (withinWindow) (prefs[countKey] ?: 1) + 1 else 1
        context.pushBatchStore.edit {
            it[tsKey] = nowMs
            it[countKey] = count
        }
        return Batch(count = count, withinWindow = withinWindow)
    }

    companion object {
        /** docs/04 §484–486: max 1 alerting push per 10 s per conversation. */
        const val WINDOW_MS = 10_000L
    }
}
