package id.nearyou.app.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val DATASTORE_NAME = "nearyou_auth_tokens"
private const val TINK_KEYSET_PREF_FILE = "nearyou_auth_tokens_tink_keyset_pref"
private const val ACCESS_SENTINEL = "at-SENTINEL"
private const val REFRESH_SENTINEL = "rt-SENTINEL"

/**
 * Real-device/emulator at-rest-encryption proof for the Android `SecureTokenStore`
 * (DataStore Preferences + Tink AEAD). Closes the
 * `mobile-auth-signin-android-instrumented-encryption-test` follow-up (the §3.5
 * raw-byte-leak + keyset-regeneration assertions deferred from Mobile #3).
 *
 * Robolectric cannot run this faithfully (its Android-Keystore shadow may no-op
 * the AEAD), so it lives in `androidInstrumentedTest` and runs on the
 * `android-instrumented` CI emulator lane (or a local device). On an emulator the
 * Tink master key is software-backed in the Android Keystore, but the DATA AEAD is
 * still real AES-256-GCM — so the raw-byte-leak assertion is meaningful.
 */
@RunWith(AndroidJUnit4::class)
class SecureTokenStoreEncryptionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = SecureTokenStore(context)

    private fun dataStoreDir() = File(context.filesDir, "datastore")

    private fun sharedPrefsDir() = File(context.applicationInfo.dataDir, "shared_prefs")

    @Before
    fun setUp() = runBlocking { store.clear() }

    @After
    fun tearDown() = runBlocking { store.clear() }

    @Test
    fun noPlaintextSentinelInAnyDataStoreOrKeysetFile() =
        runBlocking {
            store.write(TokenPair(ACCESS_SENTINEL, REFRESH_SENTINEL, 1_700_000_000_000L))
            // Round-trip sanity (also the canary that Tink AEAD actually initializes
            // on this emulator/device — if the Keystore can't init, this throws).
            assertEquals(
                TokenPair(ACCESS_SENTINEL, REFRESH_SENTINEL, 1_700_000_000_000L),
                store.read(),
            )

            val filesToScan =
                (dataStoreDir().walkTopDown() + sharedPrefsDir().walkTopDown())
                    .filter { it.isFile }
                    .toList()
            assertTrue(
                "Expected at least the DataStore .preferences_pb after write",
                filesToScan.isNotEmpty(),
            )

            val accessBytes = ACCESS_SENTINEL.encodeToByteArray()
            val refreshBytes = REFRESH_SENTINEL.encodeToByteArray()
            for (f in filesToScan) {
                val bytes = f.readBytes()
                assertFalse(
                    "Plaintext access sentinel leaked into ${f.absolutePath}",
                    bytes.containsSubsequence(accessBytes),
                )
                assertFalse(
                    "Plaintext refresh sentinel leaked into ${f.absolutePath}",
                    bytes.containsSubsequence(refreshBytes),
                )
            }
        }

    @Test
    fun dataStorePayloadFileDoesNotStorePlaintextToken() =
        runBlocking {
            store.write(TokenPair(ACCESS_SENTINEL, REFRESH_SENTINEL, 1L))
            val pb = File(dataStoreDir(), "$DATASTORE_NAME.preferences_pb")
            assertTrue("DataStore payload file missing: ${pb.absolutePath}", pb.exists())
            assertFalse(
                "DataStore payload stores the access token in plaintext",
                pb.readBytes().containsSubsequence(ACCESS_SENTINEL.encodeToByteArray()),
            )
        }

    @Test
    fun clearingTheKeysetForcesRegenerationAndOldDataIsUnreadable() =
        runBlocking {
            store.write(TokenPair(ACCESS_SENTINEL, REFRESH_SENTINEL, 1L))
            // Clear the keyset SharedPreferences (NOT a bare file delete — Android
            // caches SharedPreferences per name within a process, so a file delete
            // would not evict the in-memory keyset). A fresh AndroidKeysetManager
            // then regenerates a new keyset.
            context.getSharedPreferences(TINK_KEYSET_PREF_FILE, Context.MODE_PRIVATE)
                .edit().clear().commit()

            val regenerated = SecureTokenStore(context)
            // Old ciphertext is undecryptable under the regenerated key → null.
            assertNull(regenerated.read())
            // A fresh write under the regenerated keyset round-trips.
            regenerated.write(TokenPair("at2", "rt2", 2L))
            assertEquals(TokenPair("at2", "rt2", 2L), regenerated.read())
        }
}

/** True if [needle] occurs as a contiguous subsequence of this byte array. */
private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    outer@ for (start in 0..(size - needle.size)) {
        for (j in needle.indices) {
            if (this[start + j] != needle[j]) continue@outer
        }
        return true
    }
    return false
}
