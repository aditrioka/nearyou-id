package id.nearyou.app.infra.remoteconfig

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.slf4j.LoggerFactory

const val NEARYOU_REMOTE_CONFIG_APP_NAME: String = "nearyou-rc"

/**
 * Boot-time initialization helper for the Firebase Remote Config Admin SDK. Reads the
 * service-account JSON from the same secret slot consumed by `:infra:fcm`'s
 * `FirebaseAdminInit` (`firebase-admin-sa`), but registers a SEPARATE named
 * [FirebaseApp] (`"nearyou-rc"`) so the two consumers' lifecycles stay independent —
 * a future `:infra:firebase-app` extraction can collapse them, but is out of scope
 * here per design.md D3.
 *
 * Failure semantics mirror [`FirebaseAdminInit`](../../../../../../fcm/src/main/kotlin/id/nearyou/app/infra/fcm/FirebaseAdminInit.kt):
 *   - Empty / blank input → [RemoteConfigInitException] with `reason=blank`.
 *   - Unparseable JSON / missing required fields → [RemoteConfigInitException] with
 *     `reason=parse_failed` and the underlying cause attached.
 *   - Re-initialization of the named app → returns the existing instance (the SDK's
 *     per-name singleton invariant would otherwise reject mid-process re-init).
 *
 * Production wiring routes the typed exception to a structured FATAL log naming the
 * secret slot and exits the JVM via fail-fast — Cloud Run sees the failed health
 * check and refuses to roll forward. Test profiles skip this helper entirely.
 */
object FirebaseAdminInitForRemoteConfig {
    private val log = LoggerFactory.getLogger(FirebaseAdminInitForRemoteConfig::class.java)

    fun initialize(secretJson: String): FirebaseApp {
        if (secretJson.isBlank()) {
            throw RemoteConfigInitException("reason=blank: service-account JSON is empty / whitespace-only")
        }
        return try {
            try {
                return FirebaseApp.getInstance(NEARYOU_REMOTE_CONFIG_APP_NAME)
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
            val app = FirebaseApp.initializeApp(options, NEARYOU_REMOTE_CONFIG_APP_NAME)
            log.info("event=firebase_remote_config_initialized app_name={}", NEARYOU_REMOTE_CONFIG_APP_NAME)
            app
        } catch (e: RemoteConfigInitException) {
            throw e
        } catch (t: Throwable) {
            throw RemoteConfigInitException("reason=parse_failed: ${t.javaClass.simpleName}", t)
        }
    }
}

/**
 * Typed exception thrown by [FirebaseAdminInitForRemoteConfig.initialize]. The
 * production startup path routes this to a structured FATAL log (naming the secret
 * slot) and a non-zero exit so Cloud Run's health-check pipeline fails the deploy.
 */
class RemoteConfigInitException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
