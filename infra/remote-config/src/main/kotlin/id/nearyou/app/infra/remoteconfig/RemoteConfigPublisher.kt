package id.nearyou.app.infra.remoteconfig

import com.google.auth.oauth2.GoogleCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Write side of `:infra:remote-config` — a SIBLING interface to the read-only
 * [RemoteConfigClient] (interface segregation: the moderation read-consumers
 * `ModerationListLoader` / `Layer3ConfigLoader` never see a publish method).
 *
 * Publishes single parameters to the Firebase Remote Config **Server** template
 * (the template the backend reads via [RemoteConfigClient]). The Admin Java SDK
 * has **no** server-template publish — `publishTemplate()` targets the *client*
 * template, which our reader never sees — so this uses the Remote Config **REST
 * API** against the `firebase-server` namespace with `If-Match` ETag optimistic
 * concurrency (canonical per Firebase "Modify Remote Config programmatically" +
 * the REST `projects.namespaces` resource; verified 2026-06-14). All Google/REST
 * types stay inside this module; `:backend:ktor` sees only [PublishResult] /
 * [ServerTemplateSnapshot] plain Kotlin types.
 *
 * Never throws — every failure is a typed [PublishResult] or a `null` snapshot,
 * mirroring the read side's null-on-failure contract.
 */
interface RemoteConfigPublisher {
    /**
     * True when a write transport + credentials are present. When false the
     * editor renders read-only with disabled controls (graceful degradation —
     * mirrors the FCM null-on-unconfigured precedent).
     */
    fun isConfigured(): Boolean

    /**
     * GET the server template and return its `ETag` + the raw default-value
     * string of each requested parameter (null per-parameter when absent or
     * using an in-app default). Returns `null` when unconfigured or on any read
     * error.
     */
    fun fetchServerTemplate(parameterNames: Set<String>): ServerTemplateSnapshot?

    /**
     * Patch one parameter's `defaultValue.value` into the current server
     * template and `PUT` it back with `If-Match = [expectedEtag]`. A template
     * that has advanced past [expectedEtag] since render yields
     * [PublishResult.StaleVersion] (no overwrite); a missing write permission
     * yields [PublishResult.WriteUnavailable]; the template never carries any
     * value but the single patched parameter's.
     */
    fun publishServerParameter(
        parameterName: String,
        rawValue: String,
        expectedEtag: String,
    ): PublishResult
}

/** Outcome of a single server-template parameter publish (plain Kotlin types). */
sealed interface PublishResult {
    /** The publish succeeded; [newEtag] is the template's ETag after the write (may be null). */
    data class Published(val newEtag: String?) : PublishResult

    /** The template advanced past the rendered ETag (concurrent publish) — no overwrite performed. */
    data object StaleVersion : PublishResult

    /** Write credentials / permission are absent — render read-only, no write performed. */
    data object WriteUnavailable : PublishResult

    /** Any other failure (network, malformed template, non-2xx). [reason] is a short code for logs. */
    data class Failed(val reason: String) : PublishResult
}

/**
 * Snapshot of the server template for render + concurrency: the [etag] (the
 * `If-Match` token a subsequent publish must carry), the template [version]
 * label for display, and the raw default-value string of each requested
 * [parameters] entry (null when absent / in-app-default).
 */
data class ServerTemplateSnapshot(
    val etag: String,
    val version: String?,
    val parameters: Map<String, String?>,
)

/**
 * Publisher used when no write credentials are configured (test profile, or a
 * deploy without the `firebase-admin-sa` slot). Renders read-only; every write
 * is [PublishResult.WriteUnavailable].
 */
object NoOpRemoteConfigPublisher : RemoteConfigPublisher {
    override fun isConfigured(): Boolean = false

    override fun fetchServerTemplate(parameterNames: Set<String>): ServerTemplateSnapshot? = null

    override fun publishServerParameter(
        parameterName: String,
        rawValue: String,
        expectedEtag: String,
    ): PublishResult = PublishResult.WriteUnavailable
}

/**
 * The raw REST round-trip, abstracted so tests substitute a fake (mirrors
 * [ConfigSource] on the read side). Production binding is
 * [HttpServerTemplateTransport].
 */
interface ServerTemplateTransport {
    /** GET the server template. Implementations return a non-2xx [TemplateHttpResponse] rather than throw where possible. */
    fun get(): TemplateHttpResponse

    /** PUT the full (patched) template JSON with `If-Match`. */
    fun put(
        templateJson: String,
        ifMatchEtag: String,
    ): TemplateHttpResponse
}

/** HTTP status + `ETag` header + body of a Remote Config REST round-trip. */
data class TemplateHttpResponse(
    val status: Int,
    val etag: String?,
    val body: String?,
)

/**
 * Production [RemoteConfigPublisher] over a [ServerTemplateTransport]. Holds no
 * vendor HTTP/credential types itself — those live in [HttpServerTemplateTransport]
 * so the publish/concurrency/patch logic is unit-testable with a fake transport.
 */
class FirebaseRemoteConfigServerPublisher(
    private val transport: ServerTemplateTransport,
) : RemoteConfigPublisher {
    private val log = LoggerFactory.getLogger(FirebaseRemoteConfigServerPublisher::class.java)

    override fun isConfigured(): Boolean = true

    override fun fetchServerTemplate(parameterNames: Set<String>): ServerTemplateSnapshot? {
        val resp =
            try {
                transport.get()
            } catch (t: Throwable) {
                log.warn("event=remote_config_get_failed reason={}", t.javaClass.simpleName)
                return null
            }
        if (resp.status != HTTP_OK || resp.body == null) {
            log.warn("event=remote_config_get_non_ok status={}", resp.status)
            return null
        }
        val etag = resp.etag ?: return null
        val root = parseJson(resp.body) ?: return null
        val version = extractVersion(root)
        val params = parameterNames.associateWith { extractParameterValue(root, it) }
        return ServerTemplateSnapshot(etag = etag, version = version, parameters = params)
    }

    override fun publishServerParameter(
        parameterName: String,
        rawValue: String,
        expectedEtag: String,
    ): PublishResult {
        // Re-read the template at publish time so the PUT body carries any
        // concurrent (other-parameter) changes; the If-Match on the PUT plus the
        // pre-check below close the render→publish window.
        val current =
            try {
                transport.get()
            } catch (t: Throwable) {
                return PublishResult.Failed("get:${t.javaClass.simpleName}")
            }
        when (current.status) {
            HTTP_OK -> Unit
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> return PublishResult.WriteUnavailable
            else -> return PublishResult.Failed("get_http_${current.status}")
        }
        val currentEtag = current.etag ?: return PublishResult.Failed("get_no_etag")
        // Template advanced since the page was rendered → refuse to clobber.
        if (currentEtag != expectedEtag) return PublishResult.StaleVersion
        val body = current.body ?: return PublishResult.Failed("get_no_body")
        val patched = patchParameterDefaultValue(body, parameterName, rawValue) ?: return PublishResult.Failed("patch")

        val put =
            try {
                transport.put(patched, ifMatchEtag = currentEtag)
            } catch (t: Throwable) {
                return PublishResult.Failed("put:${t.javaClass.simpleName}")
            }
        return when (put.status) {
            HTTP_OK -> PublishResult.Published(put.etag)
            HTTP_PRECONDITION_FAILED -> PublishResult.StaleVersion
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> PublishResult.WriteUnavailable
            else -> PublishResult.Failed("put_http_${put.status}")
        }
    }

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_PRECONDITION_FAILED = 412
        private val JSON = Json { ignoreUnknownKeys = true }

        internal fun parseJson(json: String): JsonObject? =
            if (json.isBlank()) {
                null
            } else {
                runCatching { JSON.parseToJsonElement(json) as? JsonObject }.getOrNull()
            }

        /** `parameters.<name>.defaultValue.value` raw string, or null if absent / in-app-default. */
        internal fun extractParameterValue(
            root: JsonObject,
            parameterName: String,
        ): String? {
            val parameters = root["parameters"] as? JsonObject ?: return null
            val parameter = parameters[parameterName] as? JsonObject ?: return null
            val defaultValue = parameter["defaultValue"] as? JsonObject ?: return null
            val value = defaultValue["value"] as? JsonPrimitive ?: return null
            return if (value.isString) value.content else null
        }

        /** Template `version.versionNumber` for display, or null. */
        internal fun extractVersion(root: JsonObject): String? {
            val version = root["version"] as? JsonObject ?: return null
            val number = version["versionNumber"] as? JsonPrimitive ?: return null
            return number.content
        }

        /**
         * Returns the full template JSON with `parameters.<name>.defaultValue`
         * replaced by `{"value": <rawValue>}`, every other field (other
         * parameters, conditions, version, parameterGroups, the parameter's own
         * `valueType` / `description`) preserved verbatim. Creates the parameter
         * if absent. Returns null when the root JSON is unparseable.
         */
        internal fun patchParameterDefaultValue(
            templateJson: String,
            parameterName: String,
            rawValue: String,
        ): String? {
            val root = parseJson(templateJson) ?: return null
            val parameters = root["parameters"] as? JsonObject
            val existing = parameters?.get(parameterName) as? JsonObject
            val newDefaultValue = buildJsonObject { put("value", JsonPrimitive(rawValue)) }
            val newParameter =
                buildJsonObject {
                    existing?.forEach { (k, v) -> if (k != "defaultValue") put(k, v) }
                    put("defaultValue", newDefaultValue)
                }
            val newParameters =
                buildJsonObject {
                    parameters?.forEach { (k, v) -> if (k != parameterName) put(k, v) }
                    put(parameterName, newParameter)
                }
            val newRoot =
                buildJsonObject {
                    root.forEach { (k, v) -> if (k != "parameters") put(k, v) }
                    put("parameters", newParameters)
                }
            return JSON.encodeToString(JsonObject.serializer(), newRoot)
        }
    }
}

/**
 * Production [ServerTemplateTransport]: the Remote Config REST round-trip over the
 * JDK [HttpClient] (JVM 21 built-in — no new dependency). Mints a short-lived
 * OAuth bearer token from the supplied [credentials] (the existing
 * `firebase-admin-sa` `GoogleCredentials`, scoped to `firebase.remoteconfig`).
 * Targets the `firebase-server` namespace so writes land on the same template the
 * backend reads.
 */
class HttpServerTemplateTransport(
    private val credentials: GoogleCredentials,
    private val projectId: String,
    private val httpClient: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS)).build(),
    baseUrl: String = DEFAULT_BASE_URL,
) : ServerTemplateTransport {
    private val endpoint = "$baseUrl/v1/projects/$projectId/namespaces/$SERVER_NAMESPACE/remoteConfig"

    override fun get(): TemplateHttpResponse {
        val request =
            HttpRequest.newBuilder(URI.create(endpoint))
                .header("Authorization", "Bearer ${freshToken()}")
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .GET()
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return response.toTemplateResponse()
    }

    override fun put(
        templateJson: String,
        ifMatchEtag: String,
    ): TemplateHttpResponse {
        val request =
            HttpRequest.newBuilder(URI.create(endpoint))
                .header("Authorization", "Bearer ${freshToken()}")
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("If-Match", ifMatchEtag)
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .PUT(HttpRequest.BodyPublishers.ofString(templateJson, Charsets.UTF_8))
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return response.toTemplateResponse()
    }

    private fun freshToken(): String {
        val scoped = credentials.createScoped(SCOPES)
        scoped.refreshIfExpired()
        return scoped.accessToken.tokenValue
    }

    private fun HttpResponse<String>.toTemplateResponse(): TemplateHttpResponse =
        TemplateHttpResponse(
            status = statusCode(),
            etag = headers().firstValue("etag").orElse(null),
            body = body(),
        )

    companion object {
        const val REQUEST_TIMEOUT_SECONDS: Long = 15
        const val DEFAULT_BASE_URL: String = "https://firebaseremoteconfig.googleapis.com"
        const val SERVER_NAMESPACE: String = "firebase-server"
        private val SCOPES = listOf("https://www.googleapis.com/auth/firebase.remoteconfig")
    }
}

/**
 * Convenience factory: builds a production [RemoteConfigPublisher] from the
 * `firebase-admin-sa` service-account JSON (the same slot
 * [firebaseRemoteConfigClient] reads). Returns [NoOpRemoteConfigPublisher]
 * (write-unavailable, render read-only) when the JSON is blank or the project id
 * cannot be derived — never throws, so a deploy missing the slot still boots and
 * the editor degrades gracefully.
 */
fun remoteConfigServerPublisher(secretJson: String): RemoteConfigPublisher {
    if (secretJson.isBlank()) return NoOpRemoteConfigPublisher
    return try {
        val credentials = secretJson.byteInputStream(Charsets.UTF_8).use { GoogleCredentials.fromStream(it) }
        val projectId = extractProjectId(secretJson) ?: return NoOpRemoteConfigPublisher
        FirebaseRemoteConfigServerPublisher(HttpServerTemplateTransport(credentials, projectId))
    } catch (t: Throwable) {
        LoggerFactory.getLogger("id.nearyou.app.infra.remoteconfig.RemoteConfigPublisher")
            .warn("event=remote_config_publisher_init_failed reason={}", t.javaClass.simpleName)
        NoOpRemoteConfigPublisher
    }
}

/** `project_id` from a service-account JSON, or null when absent / unparseable. */
private fun extractProjectId(secretJson: String): String? {
    val root = FirebaseRemoteConfigServerPublisher.parseJson(secretJson) ?: return null
    val value = root["project_id"] as? JsonPrimitive ?: return null
    return value.content.takeIf { it.isNotBlank() }
}

/**
 * Canonical JSON-array-of-strings codec for Remote Config string-list parameters
 * (`moderation_profanity_list` / `moderation_uu_ite_list`). Single-sources the wire
 * shape the moderation reader (`ModerationListLoader`) parses, so the admin
 * wordlist editor encodes exactly what the loader decodes — without leaking a
 * `kotlinx.serialization` decision into `:backend:ktor` (the editor sees only
 * `List<String>`). Encode produces `["a","b"]`; decode tolerates a malformed /
 * non-array / null value by returning `null` (the caller treats that as "no
 * current list").
 */
object RemoteConfigStringList {
    private val JSON = Json { ignoreUnknownKeys = true }

    /** `List<String>` → a JSON-array string (`["a","b"]`), the Server-template wire shape. */
    fun encode(entries: List<String>): String = JsonArray(entries.map { JsonPrimitive(it) }).toString()

    /** A JSON-array string → `List<String>`; `null` when [raw] is null, blank, malformed, or not a string array. */
    fun decode(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val element = JSON.parseToJsonElement(raw) as? JsonArray ?: return null
            element.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
        }.getOrNull()
    }
}

/**
 * Publish a string-list parameter (e.g. a moderation wordlist) to the Server
 * template: serialize [entries] to the canonical JSON-array shape and delegate to
 * [RemoteConfigPublisher.publishServerParameter] (so the If-Match optimistic
 * concurrency, the `WriteUnavailable` degradation, and the single-parameter-patch
 * guarantee all carry over unchanged). An extension (not an interface method) so
 * no implementation — including [NoOpRemoteConfigPublisher] and test fakes — needs
 * to change; the underlying `publishServerParameter` call is the spy point.
 */
fun RemoteConfigPublisher.publishServerStringList(
    parameterName: String,
    entries: List<String>,
    expectedEtag: String,
): PublishResult = publishServerParameter(parameterName, RemoteConfigStringList.encode(entries), expectedEtag)
