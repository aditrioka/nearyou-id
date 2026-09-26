# mobile-chat — delta for profile-send-message

## MODIFIED Requirements

### Requirement: Create-or-return opens or resumes a conversation

`ConversationsApiClient.createOrReturnConversation(recipientUserId)` SHALL `POST /api/v1/conversations { recipient_user_id }` and distinguish the shipped responses: `201` (new) and `200` (existing) both yield the conversation id to navigate to; `403` yields a blocked result; `400` (self) and `404` (unknown recipient) yield distinct error results. The path is exposed through the `ChatFlow` seam (`ChatFlow.createOrReturn` → `CreateConversationOutcome`). Its shipped caller is the other-user profile "Kirim pesan" action (`mobile-profile` § "\"Kirim pesan\" opens or resumes a 1:1 conversation from the other-user profile"), whose `ProfileViewModel` maps the outcome and emits the returned conversation id for the host to push `ChatThreadRoute`. `ChatThreadViewModel` SHALL NOT carry a create-or-return entry: it is keyed on an existing conversation id and is never a create-or-return caller.

#### Scenario: New and existing both return a conversation id
- **WHEN** create-or-return returns `201` (or `200`)
- **THEN** the result carries the conversation id AND the caller can navigate to `ChatThreadRoute(conversationId = …)`

#### Scenario: Block, self, and unknown are distinct results
- **WHEN** create-or-return returns `403` / `400` / `404`
- **THEN** the result maps to `Blocked` / `SelfConversation` / `RecipientNotFound` respectively (distinct, no generic fallthrough)

#### Scenario: The thread ViewModel carries no create-or-return entry
- **WHEN** inspecting `ChatThreadViewModel`
- **THEN** it declares no `startConversation` / `startedConversationId` / `startOutcome` member AND no `ChatFlow.createOrReturn` call (the profile ViewModel is the sole caller)
