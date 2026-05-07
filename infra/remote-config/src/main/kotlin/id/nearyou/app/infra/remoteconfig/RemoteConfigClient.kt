package id.nearyou.app.infra.remoteconfig

import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.KeysAndValues
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Public interface for Firebase Remote Config reads. The SDK's vendor types
 * (`ServerTemplate`, `ServerConfig`, `KeysAndValues`) are entirely encapsulated —
 * this interface returns plain Kotlin types per the
 * `### Requirement: :infra:remote-config is the sole owner of the Firebase Remote
 * Config Admin SDK` Scenario "RemoteConfigClient interface returns plain Kotlin types".
 *
 * Returns `null` for any failure mode (parameter unset, parse error, network/auth
 * error). Callers MUST coerce `null` to a scope-specific default; per-call-site
 * cascade is the canonical fallback semantics (see `:backend:ktor` `ModerationListLoader`
 * for a 4-tier example).
 *
 * **Server template (not client template).** This client reads from the Firebase
 * Remote Config **Server template** via `getServerTemplate()` (SDK ≥ 9.7.0). Server
 * templates are designed for backend services: the SDK downloads the complete
 * template (parameters + conditions) and evaluates conditions locally based on a
 * caller-supplied context. We pass an empty context — moderation wordlists are
 * platform-wide, no per-request condition evaluation needed today. If/when
 * conditions are added (e.g., env-specific overrides), the [ConfigSource]
 * indirection below makes that a one-line change at the production binding.
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
 * Lower-level abstraction: returns the raw configured string for a parameter, or
 * `null` if the parameter is unset / unreachable / SDK error. Tests substitute a
 * fake (`ConfigSource { name -> map[name] }`); production binding is
 * [FirebaseServerConfigSource] which evaluates the Firebase Server template.
 *
 * The fake-friendly indirection also keeps the SDK round-trip (network +
 * deserialization) replaceable if/when we move to a different config backend
 * (e.g., a Kubernetes ConfigMap, GCP Runtime Config) without touching the
 * caller's parsing logic.
 */
fun interface ConfigSource {
    fun fetchRawString(parameterName: String): String?
}

/**
 * Production [ConfigSource] backed by Firebase Remote Config Server template.
 * Encapsulates the `FirebaseRemoteConfig.getInstance(firebaseApp).getServerTemplate(...)`
 * + `evaluate()` round-trip; never throws (returns null on any error).
 *
 * Why server template (not client template):
 *  - Backend service per Firebase's official guidance — see Scenario "Server
 *    template" in `### Requirement: :infra:remote-config is the sole owner of
 *    the Firebase Remote Config Admin SDK`.
 *  - Server templates download the complete template + evaluate conditions
 *    locally; client templates rely on Firebase to pre-evaluate, intended for
 *    mobile/web SDKs.
 */
class FirebaseServerConfigSource(private val firebaseApp: FirebaseApp) : ConfigSource {
    private val log = LoggerFactory.getLogger(FirebaseServerConfigSource::class.java)
    private val emptyDefaults: KeysAndValues = KeysAndValues.Builder().build()

    override fun fetchRawString(parameterName: String): String? {
        return try {
            val template =
                FirebaseRemoteConfig.getInstance(firebaseApp).getServerTemplate(emptyDefaults)
            // We bypass `template.evaluate()` and parse `template.toJson()` directly.
            //
            // Why: Firebase Admin Java SDK 9.7.0+ has a bug in
            // `ConditionEvaluator.evaluateConditions(...)`:
            //
            //   checkArgument(!conditions.isEmpty(), "List of conditions must not be empty.");
            //   if (context == null || conditions.isEmpty()) { return ImmutableMap.of(); }
            //
            // Line 2 (the early-return) is unreachable because line 1 throws first. Original
            // intent was clearly that empty conditions return an empty map, but the regression
            // breaks `evaluate()` for any project that hasn't published a Server template
            // condition. We can't add a dummy condition operationally without leaving a
            // booby-trap (any operator deleting it would crash production) — so we bypass
            // `evaluate()` and read parameter `defaultValue.value` from the template JSON
            // directly. Our use case has zero per-request condition evaluation (wordlists
            // are platform-wide), so this is semantically equivalent for us.
            //
            // When the SDK fix lands, this bypass becomes equivalent-to-evaluate() and we
            // can revert to `evaluate()` + `getString()` for cleaner code. The follow-up
            // entry `firebase-admin-server-template-evaluate-bypass-removal` tracks this.
            extractParameterFromTemplateJson(template.toJson(), parameterName)
        } catch (t: Throwable) {
            log.warn(
                "event=remote_config_fetch_failed parameter={} reason={} message={}",
                parameterName,
                t.javaClass.simpleName,
                t.message ?: "(no message)",
            )
            null
        }
    }

    /**
     * Parses the template JSON returned by [com.google.firebase.remoteconfig.ServerTemplate.toJson]
     * and extracts `parameters.<parameterName>.defaultValue.value` as a raw String.
     *
     * Returns null when the parameter is absent OR has only conditional values (no default).
     * Conditional values are out of scope for this client — our wordlists are platform-wide.
     *
     * The JSON shape mirrors the Firebase Remote Config REST API:
     * ```json
     * { "parameters": { "<name>": { "defaultValue": { "value": "<raw-string>" }, "valueType": "..." } } }
     * ```
     */
    internal fun extractParameterFromTemplateJson(
        json: String,
        parameterName: String,
    ): String? {
        if (json.isBlank() || json == "{}") return null
        val root = JSON.parseToJsonElement(json) as? JsonObject ?: return null
        val parameters = root["parameters"] as? JsonObject ?: return null
        val parameter = parameters[parameterName] as? JsonObject ?: return null
        val defaultValue = parameter["defaultValue"] as? JsonObject ?: return null
        // ParameterValue can be either { "value": "..." } (Explicit) or
        // { "useInAppDefault": true } (InAppDefault sentinel). Only Explicit has a usable
        // value for our bypass; InAppDefault is treated as "unset" to match the evaluate()
        // semantics.
        val value = defaultValue["value"] as? JsonPrimitive ?: return null
        return if (value.isString) value.content else null
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Production binding. Reads parameters from the supplied [ConfigSource], parses
 * the raw string into the typed return shape, logs WARN on parse errors.
 *
 * Failure modes (all return `null`):
 *  - Parameter is absent from the template (ConfigSource returns null).
 *  - String-list parameter is set but does not parse as a JSON array of strings.
 *  - Integer parameter is set but does not parse as `Int`.
 *  - Boolean parameter is set but is not the case-insensitive literal `"true"` / `"false"`.
 *  - Any [Throwable] from the SDK (network, auth, IO) — caught + logged WARN inside
 *    [FirebaseServerConfigSource]; null returned.
 *
 * The class never throws; production callers' fallback ladders depend on `null`-on-failure
 * semantics.
 */
class FirebaseRemoteConfigClient(
    private val configSource: ConfigSource,
) : RemoteConfigClient {
    private val log = LoggerFactory.getLogger(FirebaseRemoteConfigClient::class.java)

    override fun fetchStringList(parameterName: String): List<String>? {
        val raw = configSource.fetchRawString(parameterName) ?: return null
        return parseStringList(raw, parameterName)
    }

    override fun fetchInt(parameterName: String): Int? {
        val raw = configSource.fetchRawString(parameterName) ?: return null
        return raw.trim().toIntOrNull()
    }

    override fun fetchBoolean(parameterName: String): Boolean? {
        val raw = configSource.fetchRawString(parameterName) ?: return null
        return parseBoolean(raw)
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

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Convenience factory: bootstraps the Firebase Admin SDK with the given service-account
 * JSON, then wraps the resulting named [com.google.firebase.FirebaseApp] in a
 * [FirebaseRemoteConfigClient] backed by [FirebaseServerConfigSource]. Returns the
 * client as the public [RemoteConfigClient] interface so `:backend:ktor` consumers
 * do not import any `com.google.firebase.*` symbols.
 *
 * Throws [RemoteConfigInitException] if the service account JSON is empty or
 * unparseable; production callers should fail-fast on this exception so Cloud Run
 * health-check pipeline rejects the deploy.
 */
fun firebaseRemoteConfigClient(secretJson: String): RemoteConfigClient {
    val firebaseApp = FirebaseAdminInitForRemoteConfig.initialize(secretJson)
    return FirebaseRemoteConfigClient(FirebaseServerConfigSource(firebaseApp))
}
