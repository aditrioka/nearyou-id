package id.nearyou.app.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Contract test for the `SecureTokenStore` API shape, exercised against an in-memory
 * test fake (`InMemoryTokenStore`). The platform actuals (Android DataStore + Tink,
 * iOS Keychain) are NOT exercised here — they need real platform contexts and ship via
 * the §10 manual smoke per `tasks.md` (uninstall+reinstall, OS-reboot, etc.).
 *
 * This contract verifies the spec scenarios "write-then-read round-trip preserves the
 * TokenPair" and "clear-then-read returns null" — see
 * `openspec/specs/mobile-auth-signin/spec.md` § "SecureTokenStore persists tokens at
 * rest with platform-specific encryption".
 */
class SecureTokenStoreContractTest {
    @Test
    fun `write then read returns the same TokenPair`() =
        runTest {
            val store = InMemoryTokenStore()
            val tokens = TokenPair("at-X", "rt-Y", 1234567890L)

            store.write(tokens)
            val readBack = store.read()

            assertEquals(tokens, readBack)
        }

    @Test
    fun `read on empty store returns null`() =
        runTest {
            val store = InMemoryTokenStore()
            assertNull(store.read())
        }

    @Test
    fun `clear then read returns null`() =
        runTest {
            val store = InMemoryTokenStore()
            store.write(TokenPair("at-X", "rt-Y", 1234567890L))

            store.clear()
            val readBack = store.read()

            assertNull(readBack)
        }

    @Test
    fun `write overwrites a previous TokenPair`() =
        runTest {
            val store = InMemoryTokenStore()
            store.write(TokenPair("at-old", "rt-old", 1L))

            store.write(TokenPair("at-new", "rt-new", 2L))
            val readBack = store.read()

            assertEquals(TokenPair("at-new", "rt-new", 2L), readBack)
        }

    @Test
    fun `clear on empty store is a no-op (does not throw)`() =
        runTest {
            val store = InMemoryTokenStore()
            store.clear()
            assertNull(store.read())
        }
}

/**
 * Test-only in-memory implementation of the SecureTokenStore contract. Mirrors the API
 * shape declared by [SecureTokenStore] but with a backing reference instead of platform
 * encryption — used to verify the contract behavior without requiring a platform context.
 */
internal class InMemoryTokenStore {
    private var current: TokenPair? = null

    suspend fun read(): TokenPair? = current

    suspend fun write(tokens: TokenPair) {
        current = tokens
    }

    suspend fun clear() {
        current = null
    }
}
