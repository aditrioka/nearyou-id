package id.nearyou.app.chat

import java.util.UUID

/**
 * Thin orchestrator over [ChatRepository]. Surfaces a sealed result type for create-or-return
 * so the route handler can map exception cases (block / self-DM / recipient-missing) to the
 * canonical 403 / 400 / 404 responses without coupling to the repository's exception classes.
 *
 * Send/list-messages/list-conversations pass through to the repository directly — there is no
 * rate-limit gate yet (chat-rate-limit is a follow-up change per `chat-foundation` design).
 */
class ChatService(
    private val repository: ChatRepository,
) {
    sealed interface CreateConversationResult {
        data class Created(val outcome: CreateConversationOutcome) : CreateConversationResult

        data class Returned(val outcome: CreateConversationOutcome) : CreateConversationResult

        data object Blocked : CreateConversationResult

        data object SelfDm : CreateConversationResult

        data object RecipientNotFound : CreateConversationResult
    }

    fun createConversation(
        callerId: UUID,
        recipientId: UUID,
    ): CreateConversationResult {
        return try {
            val outcome = repository.findOrCreate1to1(callerId, recipientId)
            if (outcome.wasCreated) {
                CreateConversationResult.Created(outcome)
            } else {
                CreateConversationResult.Returned(outcome)
            }
        } catch (_: SelfDmException) {
            CreateConversationResult.SelfDm
        } catch (_: RecipientNotFoundException) {
            CreateConversationResult.RecipientNotFound
        } catch (_: BlockedException) {
            CreateConversationResult.Blocked
        }
    }

    fun listMyConversations(
        viewerId: UUID,
        cursor: ConversationsCursor?,
        limit: Int,
    ): List<ConversationListRow> = repository.listMyConversations(viewerId, cursor, limit)

    fun listMessages(
        conversationId: UUID,
        viewerId: UUID,
        cursor: MessagesCursor?,
        limit: Int,
    ): List<ChatMessageRow> = repository.listMessages(conversationId, viewerId, cursor, limit)

    fun sendMessage(
        conversationId: UUID,
        senderId: UUID,
        content: String,
    ): ChatMessageRow = repository.sendMessage(conversationId, senderId, content)

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 50
        const val MAX_PAGE_SIZE: Int = 100
    }
}
