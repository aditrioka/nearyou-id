package id.nearyou.app.infra.fcm

import id.nearyou.data.repository.NotificationRow
import id.nearyou.data.repository.NotificationType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID

/**
 * Structural assertions on the Firebase Admin SDK `Message` wire payloads
 * produced by [buildAndroidMessage] / [buildIosMessage], using [MessageInspector]
 * to render the SDK's transport JSON OFFLINE (no network, no credentials).
 *
 * Closes the earlier `fcm-payload-structural-tests` follow-up: the
 * `fcm-push-dispatch` spec scenarios "Android payload has no notification
 * block", "Android payload sets priority HIGH", "iOS payload sets
 * aps.mutable-content = true", and "iOS payload carries body_full as
 * JSON-stringified body_data" were previously smoke-tested only (builder
 * returns non-null). They are now asserted against the actual wire structure.
 */
class MessagePayloadStructuralTest : StringSpec(
    {
        val recipient = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val targetId = UUID.randomUUID()

        fun row(
            type: NotificationType = NotificationType.POST_LIKED,
            actorUserId: UUID? = actor,
            targetType: String? = "post",
            targetIdValue: UUID? = targetId,
            bodyDataJson: String? = """{"post_excerpt":"Hi from Jakarta"}""",
        ): NotificationRow =
            NotificationRow(
                id = UUID.randomUUID(),
                userId = recipient,
                type = type,
                actorUserId = actorUserId,
                targetType = targetType,
                targetId = targetIdValue,
                bodyDataJson = bodyDataJson,
                createdAt = Instant.now(),
                readAt = null,
            )

        // ---- Android structural assertions --------------------------------

        "android payload is data-only: no notification block, priority HIGH, data fields present" {
            val wire = MessageInspector.toWireJson(buildAndroidMessage(row(), "bobby", "tok-android"))

            // Spec: "Android payload has no notification block" (data-only push).
            wire["notification"].shouldBeNull()
            // Spec: "Android payload sets priority HIGH" — the SDK lowercases the
            // AndroidConfig.Priority enum on the wire.
            wire["android"]!!.jsonObject["priority"]!!.jsonPrimitive.content shouldBe "high"

            val data = wire["data"]!!.jsonObject
            data["type"]!!.jsonPrimitive.content shouldBe NotificationType.POST_LIKED.wire
            data["actor_user_id"]!!.jsonPrimitive.content shouldBe actor.toString()
            // mobile-push-message-handling MODIFY: the masked username rides
            // alongside the routing UUID (render-only vs routing-only).
            data["actor_username"]!!.jsonPrimitive.content shouldBe "bobby"
            data["target_type"]!!.jsonPrimitive.content shouldBe "post"
            data["target_id"]!!.jsonPrimitive.content shouldBe targetId.toString()
            data["body_data"]!!.jsonPrimitive.content shouldBe """{"post_excerpt":"Hi from Jakarta"}"""
            wire["token"]!!.jsonPrimitive.content shouldBe "tok-android"
        }

        "android payload with null actor/target/body_data still has no notification block + empty-string data" {
            val wire =
                MessageInspector.toWireJson(
                    buildAndroidMessage(
                        row(
                            type = NotificationType.PRIVACY_FLIP_WARNING,
                            actorUserId = null,
                            targetType = null,
                            targetIdValue = null,
                            bodyDataJson = null,
                        ),
                        actorUsername = null,
                        token = "tok",
                    ),
                )

            wire["notification"].shouldBeNull()
            val data = wire["data"]!!.jsonObject
            // Builder maps absent fields to empty strings (never drops the keys).
            data["actor_user_id"]!!.jsonPrimitive.content shouldBe ""
            data["actor_username"]!!.jsonPrimitive.content shouldBe ""
            data["target_type"]!!.jsonPrimitive.content shouldBe ""
            data["body_data"]!!.jsonPrimitive.content shouldBe ""
        }

        "android payload masks a shadow-banned non-null actor to Seseorang on the wire" {
            // Spec scenario: "Android actor_username masks a shadow-banned non-null
            // actor to Seseorang, never the empty string or the real handle".
            val wire =
                MessageInspector.toWireJson(
                    buildAndroidMessage(row(), actorUsername = null, token = "tok"),
                )
            wire["data"]!!.jsonObject["actor_username"]!!.jsonPrimitive.content shouldBe "Seseorang"
        }

        "android payload passes a REAL empty-object body_data through as {} (followed shape)" {
            // Pre-fix the builder collapsed the literal {} to "" — indistinguishable
            // from NULL on the client. Spec: "" is reserved for body_data IS NULL.
            val wire =
                MessageInspector.toWireJson(
                    buildAndroidMessage(row(bodyDataJson = "{}"), "bobby", "tok"),
                )
            wire["data"]!!.jsonObject["body_data"]!!.jsonPrimitive.content shouldBe "{}"
        }

        // ---- iOS structural assertions ------------------------------------

        "ios payload sets aps.mutable-content=1, carries body_full, and has the alert block" {
            val result = buildIosMessage(row(), actorUsername = "bobby", token = "tok-ios")
            check(result is IosPayloadResult.Built)
            val wire = MessageInspector.toWireJson(result.message)

            val payload = wire["apns"]!!.jsonObject["payload"]!!.jsonObject
            // Spec: "iOS payload sets aps.mutable-content = true" — APNs wire form is int 1.
            payload["aps"]!!.jsonObject["mutable-content"]!!.jsonPrimitive.int shouldBe 1
            // Spec: "iOS payload carries body_full as JSON-stringified body_data".
            payload["body_full"]!!.jsonPrimitive.content shouldBe """{"post_excerpt":"Hi from Jakarta"}"""
            // Spec: "iOS payload carries the tap-routing data fields" — delivered in
            // userInfo at tap time so the delegate can feed the shared resolver.
            payload["type"]!!.jsonPrimitive.content shouldBe NotificationType.POST_LIKED.wire
            payload["target_type"]!!.jsonPrimitive.content shouldBe "post"
            payload["target_id"]!!.jsonPrimitive.content shouldBe targetId.toString()

            // iOS is an alert push (unlike Android) — title + body present.
            val notification = wire["notification"]!!.jsonObject
            notification["title"].shouldNotBeNull()
            notification["body"]!!.jsonPrimitive.content shouldBe PushCopy.bodyFor("post_liked", "bobby")
        }

        "ios routing fields use the same empty-string-when-null semantics as Android" {
            val result =
                buildIosMessage(
                    row(
                        type = NotificationType.PRIVACY_FLIP_WARNING,
                        actorUserId = null,
                        targetType = null,
                        targetIdValue = null,
                        bodyDataJson = null,
                    ),
                    actorUsername = null,
                    token = "tok",
                )
            check(result is IosPayloadResult.Built)
            val payload =
                MessageInspector.toWireJson(result.message)["apns"]!!
                    .jsonObject["payload"]!!.jsonObject
            payload["type"]!!.jsonPrimitive.content shouldBe NotificationType.PRIVACY_FLIP_WARNING.wire
            payload["target_type"]!!.jsonPrimitive.content shouldBe ""
            payload["target_id"]!!.jsonPrimitive.content shouldBe ""
        }

        "ios chat_message payload: notification.body uses chat copy, body_full carries the data keys" {
            val convId = UUID.randomUUID()
            val result =
                buildIosMessage(
                    row(
                        type = NotificationType.CHAT_MESSAGE,
                        targetType = "message",
                        bodyDataJson = """{"conversation_id":"$convId","preview":"halo Alice"}""",
                    ),
                    actorUsername = "bobby",
                    token = "tok-ios",
                )
            check(result is IosPayloadResult.Built)
            val wire = MessageInspector.toWireJson(result.message)

            wire["notification"]!!.jsonObject["body"]!!.jsonPrimitive.content shouldBe
                PushCopy.bodyFor("chat_message", "bobby")
            wire["apns"]!!.jsonObject["payload"]!!.jsonObject["body_full"]!!.jsonPrimitive.content shouldBe
                """{"conversation_id":"$convId","preview":"halo Alice"}"""
        }
    },
)
