package id.nearyou.app.infra.fcm

import com.google.api.client.json.gson.GsonFactory
import com.google.firebase.messaging.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Offline structural inspector for Firebase Admin SDK [Message] payloads.
 *
 * The SDK's `Message` / `AndroidConfig` / `ApnsConfig` / `Aps` classes expose
 * no public read-side accessors, so structural assertions historically needed
 * either Java reflection on `@Key` private fields (brittle) or a live
 * `FirebaseMessaging.send(msg, dryRun=true)` (network + credentials, not
 * CI-viable). This helper instead taps the SDK's OWN transport-serialization
 * seam offline: the package-private `Message.wrapForTransport(false)` returns
 * the `{"message": <@Key tree>}` map the SDK would POST to FCM (no
 * `validate_only`, no HTTP), and the Google HTTP-client `GsonFactory`
 * (transitive via firebase-admin) renders that `@Key` tree to JSON.
 *
 * One reflected method name is the only brittleness point; it fails loudly on
 * an SDK bump — the same philosophy as the existing `extractTokenFromMessage`
 * reflection helper in `FcmDispatcherTest`.
 */
object MessageInspector {
    private val wrapForTransport =
        Message::class.java
            .getDeclaredMethod("wrapForTransport", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }

    /** Render [message] to the FCM wire JSON (the `message` object the SDK sends). */
    fun toWireJson(message: Message): JsonObject {
        @Suppress("UNCHECKED_CAST")
        val transport = wrapForTransport.invoke(message, false) as Map<String, Any?>
        val rendered = GsonFactory.getDefaultInstance().toString(transport)
        return Json.parseToJsonElement(rendered).jsonObject["message"]!!.jsonObject
    }
}
