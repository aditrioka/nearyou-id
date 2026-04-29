package id.nearyou.app.infra.fcm

import id.nearyou.data.repository.NotificationRow
import id.nearyou.data.repository.NotificationType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID

/**
 * Covers `fcm-push-dispatch` spec § "Android payload SHALL be data-only" and
 * § "iOS payload SHALL be alert + mutable-content with body_full data field,
 * clamped to APNs 4 KB limit". Tasks 6.2, 6.3, 6.3.a, 6.3.b, 6.3.c, 6.3.d.
 *
 * Limitations: the Firebase Admin SDK's `Message`, `AndroidConfig`, and
 * `ApnsConfig` classes use package-private fields (Java `@Key` annotations
 * on private members) and provide no public read-side accessor surface.
 * Direct structural reflection produces brittle tests that drift on SDK
 * minor bumps. This test suite therefore focuses on what is testable
 * without poking SDK internals:
 *
 *   - `buildAndroidMessage` and `buildIosMessage` execute successfully
 *     (no exceptions for the documented inputs).
 *   - `IosPayloadResult.Built` vs `IosPayloadResult.OversizedPayload`
 *     distinction (this is the one bit of behaviour `FcmDispatcher` actually
 *     branches on).
 *   - `bodyFull` clamping behaviour: under-threshold passes through,
 *     over-threshold is byte-clamped on a UTF-8 codepoint boundary,
 *     pathology returns `OversizedPayload`.
 *   - JSON validity + Unicode round-trip on `bodyFull`.
 *
 * Structural assertions on the `Message` internals (no notification block,
 * priority HIGH, mutable-content=true, body_full custom-data presence) are
 * deferred to the integration-test surface, which can exercise
 * `FirebaseMessaging.send(msg, dryRun=true)` against a real (test-only)
 * FirebaseApp — see `FOLLOW_UPS.md` entry "fcm-payload-structural-tests".
 */
class PayloadBuildersTest : StringSpec(
    {
        val recipient = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val targetId = UUID.randomUUID()

        fun row(
            type: NotificationType = NotificationType.POST_LIKED,
            actorUserId: UUID? = actor,
            targetType: String? = "post",
            targetIdValue: UUID? = targetId,
            bodyDataJson: String = """{"post_excerpt":"Hi from Jakarta"}""",
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

        // ---- Android payload tests (task 6.2) ----------------------------

        "android payload builder returns a non-null Message for the canonical post_liked input" {
            val msg = buildAndroidMessage(row(), "tok-android-1")
            msg shouldNotBe null
        }

        "android payload builder tolerates null actor / target / body_data without throwing" {
            // Smoke: covers spec scenarios 'Android payload tolerates null actor and target',
            // 'Android payload tolerates null body_data' — exercises the empty-string
            // fallback paths in the builder. Structural verification of the resulting
            // map is deferred per the file-level note.
            val msg =
                buildAndroidMessage(
                    row(
                        type = NotificationType.PRIVACY_FLIP_WARNING,
                        actorUserId = null,
                        targetType = null,
                        targetIdValue = null,
                        bodyDataJson = "{}",
                    ),
                    "tok",
                )
            msg shouldNotBe null
        }

        // ---- iOS payload tests (tasks 6.3, 6.3.a-d) ----------------------

        "ios payload builder returns Built for the canonical post_liked input" {
            val result = buildIosMessage(row(), actorUsername = "bobby", token = "tok-ios-1")
            check(result is IosPayloadResult.Built)
            // bodyFull starts as the input json; trivially clamped (input < threshold).
            result.bodyFull shouldBe """{"post_excerpt":"Hi from Jakarta"}"""
        }

        "ios payload below clamp threshold is unmodified (task 6.3 unmodified-below path)" {
            val small = """{"post_excerpt":"Halo"}"""
            val result = buildIosMessage(row(bodyDataJson = small), actorUsername = "bobby", token = "tok")
            check(result is IosPayloadResult.Built)
            result.bodyFull shouldBe small
        }

        "ios payload clamps oversized body_full to ≤ 4 KB AND preserves JSON structure (task 6.3.a)" {
            val excerpt = "x".repeat(5000)
            val raw = """{"post_excerpt":"$excerpt","reply_id":"abc"}"""
            val result = buildIosMessage(row(bodyDataJson = raw), actorUsername = "bobby", token = "tok")
            check(result is IosPayloadResult.Built)
            (
                result.bodyFull.toByteArray(Charsets.UTF_8).size <=
                    APNS_PAYLOAD_BUDGET_BYTES - APNS_NOTIFICATION_OVERHEAD_BYTES
            ) shouldBe true
            // Parses as JSON, both keys present, post_excerpt was shortened.
            val parsed = Json.parseToJsonElement(result.bodyFull).jsonObject
            parsed["reply_id"]?.jsonPrimitive?.content shouldBe "abc"
            ((parsed["post_excerpt"]?.jsonPrimitive?.content?.length ?: 0) < excerpt.length) shouldBe true
        }

        "ios payload preserves Unicode + escapes round-trip (task 6.3.b)" {
            val raw =
                Json.encodeToString(
                    JsonObject.serializer(),
                    JsonObject(
                        mapOf(
                            "post_excerpt" to JsonPrimitive("Hi from Jakarta — \"Selamat datang\" 中田 🎉"),
                        ),
                    ),
                )
            val result = buildIosMessage(row(bodyDataJson = raw), actorUsername = "bobby", token = "tok")
            check(result is IosPayloadResult.Built)
            // Parses back; embedded string equals original.
            val parsed = Json.parseToJsonElement(result.bodyFull).jsonObject
            parsed["post_excerpt"]?.jsonPrimitive?.content shouldBe
                "Hi from Jakarta — \"Selamat datang\" 中田 🎉"
        }

        "ios clamp produces well-formed UTF-16 even when truncation crosses an emoji boundary (task 6.3.c)" {
            val prefix = "x".repeat(3000)
            val emoji = "🎉"
            val tail = "y".repeat(5000)
            val excerpt = prefix + emoji + tail
            val raw =
                Json.encodeToString(
                    JsonObject.serializer(),
                    JsonObject(mapOf("reply_excerpt" to JsonPrimitive(excerpt))),
                )
            val result = buildIosMessage(row(bodyDataJson = raw), actorUsername = "bobby", token = "tok")
            check(result is IosPayloadResult.Built)
            // Validity contract: re-parsing as JSON succeeds AND the truncated
            // string round-trips through UTF-8 lossly (no orphan surrogate
            // would survive UTF-8 → UTF-16 round-trip cleanly).
            val truncated =
                Json.parseToJsonElement(result.bodyFull).jsonObject["reply_excerpt"]!!.jsonPrimitive.content
            truncated.hasNoOrphanSurrogates() shouldBe true
        }

        "ios pathology: body_data with no single field large enough to truncate returns OversizedPayload (task 6.3.d)" {
            val keys = (1..25).joinToString(",") { i -> """"k$i":"${"y".repeat(200)}"""" }
            val raw = "{$keys}"
            val result = buildIosMessage(row(bodyDataJson = raw), actorUsername = "bobby", token = "tok")
            result shouldBe IosPayloadResult.OversizedPayload
        }
    },
)

/**
 * Returns true when [this] string contains no orphan surrogates — every high
 * surrogate is paired with a low surrogate immediately following, and there
 * are no isolated low surrogates. Used to validate the UTF-8-safe truncator's
 * output.
 */
private fun String.hasNoOrphanSurrogates(): Boolean {
    var i = 0
    while (i < length) {
        val c = this[i]
        when {
            c.isHighSurrogate() -> {
                if (i + 1 >= length || !this[i + 1].isLowSurrogate()) return false
                i += 2
            }
            c.isLowSurrogate() -> return false
            else -> i++
        }
    }
    return true
}
