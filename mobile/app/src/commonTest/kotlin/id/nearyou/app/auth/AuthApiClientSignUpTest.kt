package id.nearyou.app.auth

import id.nearyou.app.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SIGNUP_CREATED_BODY =
    """{"access_token":"at-new","refresh_token":"rt-new","expires_in":900}"""

private fun OutgoingContent.bodyText(): String = (this as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""

/**
 * MockEngine coverage of the `AuthApiClient.signUp` wire contract (Mobile #4, tasks 7.4 + 7.12):
 * canonical `/api/v1/auth/signup` endpoint, snake_case body `{provider, id_token, date_of_birth}`
 * with NO `device_fingerprint_hash` (design D4), and the `201 Created` token parse. The status →
 * outcome mapping is exercised at the repository level (`AuthRepositorySignUpTest`).
 */
class AuthApiClientSignUpTest {
    private fun client(
        tokenStore: TokenStore,
        handler: MockRequestHandler,
    ): HttpClient =
        HttpClientFactory.create(
            installTimeouts = false,
            apiBaseUrl = "http://test.local",
            tokenStore = tokenStore,
            sessionInvalidator = SessionInvalidator(tokenStore),
            engine = MockEngine(handler),
            installLogging = false,
            nowMillis = { 0L },
        )

    // 7.4 — canonical endpoint + snake_case body + no device_fingerprint_hash.
    @Test
    fun `signup posts provider id_token date_of_birth in snake_case and omits device_fingerprint_hash`() =
        runTest {
            var capturedBody = ""
            var capturedPath = ""
            val store = InMemoryTokenStore()
            val httpClient =
                client(store) { request ->
                    capturedPath = request.url.encodedPath
                    capturedBody = request.body.bodyText()
                    respond(SIGNUP_CREATED_BODY, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                }

            val result = AuthApiClient(httpClient) { 0L }.signUp(idToken = "g-id", dateOfBirth = "1995-03-14")

            assertTrue(result is SignInApiResult.Success)
            assertEquals("/api/v1/auth/signup", capturedPath)
            assertTrue(capturedBody.contains("\"provider\""), "body: $capturedBody")
            assertTrue(capturedBody.contains("google"), "body: $capturedBody")
            // Pin the literal snake_case wire KEYS (mirrors the backend AuthWireFormatTest signup pin).
            assertTrue(capturedBody.contains("\"id_token\""), "wire key must be id_token: $capturedBody")
            assertTrue(capturedBody.contains("g-id"), "id_token value carried: $capturedBody")
            assertTrue(capturedBody.contains("\"date_of_birth\""), "wire key must be date_of_birth: $capturedBody")
            assertTrue(capturedBody.contains("1995-03-14"), "DOB carried as ISO date: $capturedBody")
            assertFalse(capturedBody.contains("idToken"), "must NOT use camelCase idToken: $capturedBody")
            assertFalse(capturedBody.contains("dateOfBirth"), "must NOT use camelCase dateOfBirth: $capturedBody")
            assertFalse(capturedBody.contains("device_fingerprint_hash"), "must omit fingerprint: $capturedBody")
        }

    // 7.12-adjacent — the DTO (de)serializes snake_case independently of any round-trip.
    @Test
    fun `SignUpRequest serializes snake_case wire field names`() {
        val encoded =
            Json.encodeToString(
                SignUpRequest.serializer(),
                SignUpRequest(provider = "google", idToken = "tok", dateOfBirth = "2000-01-01"),
            )
        assertTrue(encoded.contains("\"id_token\""), encoded)
        assertTrue(encoded.contains("\"date_of_birth\""), encoded)
        assertFalse(encoded.contains("idToken"), encoded)
        assertFalse(encoded.contains("dateOfBirth"), encoded)
        assertFalse(encoded.contains("device_fingerprint_hash"), encoded)
    }

    // mobile-referral — an entered invite code is forwarded as the snake_case invite_code key.
    @Test
    fun `signup with an entered invite code includes invite_code`() =
        runTest {
            var capturedBody = ""
            val httpClient =
                client(InMemoryTokenStore()) { request ->
                    capturedBody = request.body.bodyText()
                    respond(SIGNUP_CREATED_BODY, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                }

            AuthApiClient(httpClient) { 0L }.signUp(idToken = "g-id", dateOfBirth = "1995-03-14", inviteCode = "a3f7k2mq")

            val obj = Json.parseToJsonElement(capturedBody).jsonObject
            assertEquals("a3f7k2mq", obj["invite_code"]?.jsonPrimitive?.content, "body: $capturedBody")
        }

    // mobile-referral — a blank invite code omits the invite_code key entirely (not a null value).
    @Test
    fun `signup with a blank invite code omits the invite_code key`() =
        runTest {
            var capturedBody = ""
            val httpClient =
                client(InMemoryTokenStore()) { request ->
                    capturedBody = request.body.bodyText()
                    respond(SIGNUP_CREATED_BODY, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                }

            AuthApiClient(httpClient) { 0L }.signUp(idToken = "g-id", dateOfBirth = "1995-03-14", inviteCode = "   ")

            val obj = Json.parseToJsonElement(capturedBody).jsonObject
            assertFalse(obj.containsKey("invite_code"), "blank input must omit the key: $capturedBody")
        }

    // mobile-referral — the default (no invite code passed) likewise omits the key (back-compat).
    @Test
    fun `signup with no invite code omits the invite_code key`() =
        runTest {
            var capturedBody = ""
            val httpClient =
                client(InMemoryTokenStore()) { request ->
                    capturedBody = request.body.bodyText()
                    respond(SIGNUP_CREATED_BODY, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                }

            AuthApiClient(httpClient) { 0L }.signUp(idToken = "g-id", dateOfBirth = "1995-03-14")

            assertFalse(capturedBody.contains("invite_code"), "no code → no key: $capturedBody")
        }

    // 201 Created parses the token pair (note: signup success is 201, not 200).
    @Test
    fun `signup 201 parses the token pair with absolute expiry`() =
        runTest {
            val store = InMemoryTokenStore()
            val httpClient =
                client(store) {
                    respond(SIGNUP_CREATED_BODY, HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
                }

            val result = AuthApiClient(httpClient) { 0L }.signUp("g-id", "1995-03-14")

            assertTrue(result is SignInApiResult.Success)
            assertEquals(TokenPair("at-new", "rt-new", 900_000L), result.tokens)
        }
}
