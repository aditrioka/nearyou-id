package id.nearyou.app.infra.fcm

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.slf4j.LoggerFactory

const val NEARYOU_FIREBASE_APP_NAME = "nearyou-default"

/**
 * Boot-time initialization helper for the Firebase Admin SDK. Reads the
 * service-account JSON via [secretJson], parses it, validates it, and
 * registers the named [FirebaseApp] singleton (`"nearyou-default"`) per
 * `openspec/changes/fcm-push-dispatch/design.md` D7.
 *
 * Failure semantics:
 *   - Empty / blank input → throws [FcmInitException] with `reason=blank`.
 *   - Unparseable JSON / missing required Firebase service-account fields →
 *     throws [FcmInitException] with `reason=parse_failed` and the underlying
 *     cause attached.
 *   - Re-initialization (already-registered app name) → returns the existing
 *     `FirebaseApp` instead of throwing; the SDK's per-name singleton invariant
 *     would otherwise reject re-init mid-process. (Per D15: rotation is a
 *     restart, not a hot-reload; re-init guard is purely a safety net for the
 *     rare double-startup edge.)
 *
 * Production wiring routes the typed exception to a structured FATAL log naming
 * the secret slot and exits the JVM via fail-fast — Cloud Run sees the failed
 * health check and refuses to roll forward. Test profiles skip this helper
 * entirely.
 */
object FirebaseAdminInit {
    private val log = LoggerFactory.getLogger(FirebaseAdminInit::class.java)

    fun initialize(secretJson: String): FirebaseApp {
        if (secretJson.isBlank()) {
            throw FcmInitException("reason=blank: service-account JSON is empty / whitespace-only")
        }
        return try {
            // Try to find a previously-registered named app; SDK throws if missing.
            try {
                return FirebaseApp.getInstance(NEARYOU_FIREBASE_APP_NAME)
            } catch (_: IllegalStateException) {
                // Not registered yet — proceed with first-time init below.
            }

            val credentials =
                secretJson.byteInputStream(Charsets.UTF_8).use { stream ->
                    GoogleCredentials.fromStream(stream)
                }
            val options =
                FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build()
            val app = FirebaseApp.initializeApp(options, NEARYOU_FIREBASE_APP_NAME)
            log.info("event=firebase_admin_initialized app_name={}", NEARYOU_FIREBASE_APP_NAME)
            app
        } catch (e: FcmInitException) {
            throw e
        } catch (t: Throwable) {
            throw FcmInitException("reason=parse_failed: ${t.javaClass.simpleName}", t)
        }
    }

    /**
     * Returns the [FirebaseMessaging] instance bound to the named
     * [FirebaseApp]. Production DI calls this once and caches the result.
     */
    fun messaging(): FirebaseMessaging = FirebaseMessaging.getInstance(FirebaseApp.getInstance(NEARYOU_FIREBASE_APP_NAME))
}

/**
 * Typed exception thrown by [FirebaseAdminInit.initialize]. The production
 * startup path routes this to a structured FATAL log (naming the secret slot)
 * and a non-zero exit so Cloud Run's health-check pipeline fails the deploy.
 */
class FcmInitException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
