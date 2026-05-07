package id.nearyou.app.infra.remoteconfig

import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ParameterValue
import com.google.firebase.remoteconfig.Template
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Public interface for Firebase Remote Config reads. The SDK's vendor types
 * (`Template`, `Parameter`, `ParameterValue`) are entirely encapsulated — this
 * interface returns plain Kotlin types per the
 * `### Requirement: :infra:remote-config is the sole owner of the Firebase Remote
 * Config Admin SDK` Scenario "RemoteConfigClient interface returns plain Kotlin types".
 *
 * Returns `null` for any failure mode (parameter unset, parse error, network/auth
 * error). Callers MUST coerce `null` to a scope-specific default; per-call-site
 * cascade is the canonical fallback semantics (see `:backend:ktor` `ModerationListLoader`
 * for a 4-tier example).
 */
interface RemoteConfigClient {
    /** Returns parsed string-array value, or `null` for unset / malformed / SDK-error cases. */
    fun fetchStringList(parameterName: String): List<String>?

    /** Returns parsed integer value, or `null` for unset / non-integer / SDK-error cases. */
    fun fetchInt(parameterName: String): Int?

    /** Returns parsed boolean value, or `null` for unset / non-boolean / SDK-error cases. */
    fun fetchBoolean(parameterName: String): Boolean?
}

/**
 * Functional indirection so the SDK-touching `getTemplate()` round-trip is replaceable
 * in tests. Production binding is [FirebaseTemplateProvider]. Test binding can be a
 * lambda supplying a hand-built `Template` (or throwing to simulate a network failure).
 */
fun interface TemplateProvider {
    fun fetch(): Template
}

class FirebaseTemplateProvider(private val firebaseApp: FirebaseApp) : TemplateProvider {
    override fun fetch(): Template = FirebaseRemoteConfig.getInstance(firebaseApp).template
}

/**
 * Production binding. Wraps `FirebaseRemoteConfig.getInstance(firebaseApp).template`
 * via a [TemplateProvider] indirection so tests substitute a fake template without
 * needing a real Firebase project.
 *
 * Failure modes (all return `null`):
 *  - Parameter is absent from the template.
 *  - Parameter's default value is `useInAppDefault` (the SDK sentinel for "fall back to
 *    the on-device default") — treated as "not set" so the loader cascade proceeds.
 *  - String-list parameter is set but does not parse as a JSON array of strings.
 *  - Integer parameter is set but does not parse as `Int`.
 *  - Boolean parameter is set but is not the case-insensitive literal `"true"` / `"false"`.
 *  - Any [Throwable] from the SDK (network, auth, IO) — caught + logged WARN; null returned.
 *
 * The class never throws; production callers' fallback ladders depend on `null`-on-failure
 * semantics.
 */
class FirebaseRemoteConfigClient(
    private val templateProvider: TemplateProvider,
) : RemoteConfigClient {
    private val log = LoggerFactory.getLogger(FirebaseRemoteConfigClient::class.java)

    override fun fetchStringList(parameterName: String): List<String>? {
        val raw = fetchRawString(parameterName) ?: return null
        return parseStringList(raw, parameterName)
    }

    override fun fetchInt(parameterName: String): Int? {
        val raw = fetchRawString(parameterName) ?: return null
        return raw.trim().toIntOrNull()
    }

    override fun fetchBoolean(parameterName: String): Boolean? {
        val raw = fetchRawString(parameterName) ?: return null
        return parseBoolean(raw)
    }

    private fun fetchRawString(parameterName: String): String? {
        val template =
            try {
                templateProvider.fetch()
            } catch (t: Throwable) {
                log.warn(
                    "event=remote_config_fetch_failed parameter={} reason={}",
                    parameterName,
                    t.javaClass.simpleName,
                )
                return null
            }
        val parameter = template.parameters[parameterName] ?: return null
        val defaultValue = parameter.defaultValue ?: return null
        return extractExplicitValue(defaultValue)
    }

    private fun parseStringList(
        raw: String,
        parameterName: String,
    ): List<String>? =
        try {
            val element = JSON.parseToJsonElement(raw)
            require(element is JsonArray) { "expected JSON array, got ${element::class.simpleName}" }
            element.map { item ->
                require(item is JsonPrimitive && item.isString) { "expected string elements" }
                item.jsonPrimitive.content
            }
        } catch (t: Throwable) {
            log.warn(
                "event=remote_config_parse_failed parameter={} type=string_list reason={}",
                parameterName,
                t.message ?: t.javaClass.simpleName,
            )
            null
        }

    private fun parseBoolean(raw: String): Boolean? =
        when (raw.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    /**
     * Extracts the string from a [ParameterValue.Explicit]; returns `null` for
     * [ParameterValue.InAppDefault] (the SDK's "use the on-device default" sentinel).
     * Both are public nested types of `ParameterValue` in Admin SDK 9.x; the Kotlin
     * smart-cast + `getValue()` idiom is canonical.
     */
    private fun extractExplicitValue(parameterValue: ParameterValue): String? =
        when (parameterValue) {
            is ParameterValue.Explicit -> parameterValue.value
            else -> null
        }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Convenience factory: bootstraps the Firebase Admin SDK with the given service-account
 * JSON, then wraps the resulting named [com.google.firebase.FirebaseApp] in a
 * [FirebaseRemoteConfigClient]. Returns the client as the public [RemoteConfigClient]
 * interface so `:backend:ktor` consumers do not import any `com.google.firebase.*`
 * symbols (per `### Requirement: :infra:remote-config is the sole owner of the Firebase
 * Remote Config Admin SDK`).
 *
 * Throws [RemoteConfigInitException] if the service account JSON is empty or
 * unparseable; production callers should fail-fast on this exception so Cloud Run
 * health-check pipeline rejects the deploy.
 */
fun firebaseRemoteConfigClient(secretJson: String): RemoteConfigClient {
    val firebaseApp = FirebaseAdminInitForRemoteConfig.initialize(secretJson)
    return FirebaseRemoteConfigClient(FirebaseTemplateProvider(firebaseApp))
}
