import UserNotifications

/// nearyou-id Notification Service Extension (`mobile-push-message-handling` tasks 6.2 + 6.3).
///
/// The Xcode NSE TARGET that compiles this file is operator setup (issue #430: extension target +
/// App-Group capability `group.id.nearyou.shared` on both the app and NSE targets + provisioning +
/// the `com.apple.security.application-groups` entitlement — docs/04 §494–502). Until that target
/// exists this file is inert repo source; the app builds and runs without it (design D9).
///
/// Behavior — the EXACT projection unit-tested in
/// `mobile/app/src/iosMain/kotlin/id/nearyou/app/push/NsePayloadProjection.kt`
/// (`iosSimulatorArm64Test`); keep the two in sync:
///  - Parse the `body_full` custom data field (= JSON-stringified `body_data`).
///  - Read the content-privacy preference from App-Group shared UserDefaults
///    (suite `group.id.nearyou.shared`, key `nearyou_chat_preview_enabled`, default OFF/private —
///    the same suite+key the app's `IosNotificationContentPreferenceStore` writes).
///  - ONLY when the preference is ON and the payload is a chat message (`conversation_id` present)
///    with a non-null string `preview`: rewrite the visible body to the preview. Otherwise the
///    server-built private alert body stands (malformed `body_full` included — never invent copy).
///  - Set `threadIdentifier = conversation_id` for OS-side per-conversation grouping (task 6.3).
///    This grouping is BEST-EFFORT: unlike Android's persisted count-merge, the NSE keeps no
///    durable counter, so bursts stack visually rather than merging into an "{n} pesan baru" body.
///  - Never log the preview, ids, or token.
final class NotificationService: UNNotificationServiceExtension {
    private static let appGroupSuite = "group.id.nearyou.shared"
    private static let previewEnabledKey = "nearyou_chat_preview_enabled"

    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var bestAttemptContent: UNMutableNotificationContent?

    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        self.contentHandler = contentHandler
        let content = (request.content.mutableCopy() as? UNMutableNotificationContent)
        self.bestAttemptContent = content

        guard let content else {
            contentHandler(request.content)
            return
        }

        let bodyFull = request.content.userInfo["body_full"] as? String
        let parsed = Self.parseBodyFull(bodyFull)

        // Task 6.3 — OS-grouping: thread-id = conversation_id (best-effort merge; see header).
        if let conversationId = parsed?.conversationId {
            content.threadIdentifier = conversationId
        }

        // Task 6.2 — the preference-gated preview rewrite.
        let previewEnabled =
            UserDefaults(suiteName: Self.appGroupSuite)?
                .object(forKey: Self.previewEnabledKey) as? Bool ?? false
        if previewEnabled, parsed?.conversationId != nil,
           let preview = parsed?.preview, !preview.isEmpty {
            content.body = preview
        }

        contentHandler(content)
    }

    override func serviceExtensionTimeWillExpire() {
        // The OS is about to deliver whatever we have — the server-built private body is always a
        // correct fallback.
        if let contentHandler, let bestAttemptContent {
            contentHandler(bestAttemptContent)
        }
    }

    private struct ParsedBodyData {
        let conversationId: String?
        let preview: String?
    }

    private static func parseBodyFull(_ raw: String?) -> ParsedBodyData? {
        guard
            let raw, !raw.isEmpty,
            let data = raw.data(using: .utf8),
            let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { return nil }
        let conversationId = (obj["conversation_id"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        return ParsedBodyData(
            conversationId: conversationId,
            preview: obj["preview"] as? String
        )
    }
}
