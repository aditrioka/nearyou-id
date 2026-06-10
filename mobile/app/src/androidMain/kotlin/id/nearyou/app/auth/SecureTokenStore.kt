package id.nearyou.app.auth

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException

private const val TAG = "SecureTokenStore"
private const val DATASTORE_NAME = "nearyou_auth_tokens"
private const val TINK_KEYSET_PREF_NAME = "nearyou_auth_tokens_tink_keyset"
private const val TINK_KEYSET_PREF_FILE = "nearyou_auth_tokens_tink_keyset_pref"
private const val MASTER_KEY_URI = "android-keystore://nearyou_auth_tokens_master_key"

private val ACCESS_TOKEN_KEY = stringPreferencesKey("access_token_encrypted")
private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token_encrypted")
private val EXPIRES_AT_KEY = longPreferencesKey("access_expires_at_epoch_millis")

// A corrupted prefs file resolves to empty (= signed out) instead of throwing
// CorruptionException on every cold start until the user clears app data — read()
// runs on the RootRouter routing path at every launch (2026-06-10 audit, 07-#3).
private val Context.tokenStore: DataStore<Preferences> by preferencesDataStore(
    name = DATASTORE_NAME,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Android `SecureTokenStore` actual: DataStore Preferences with Tink AEAD encryption.
 *
 * The Tink keyset is wrapped via `AndroidKeysetManager` keyed off an Android-Keystore master
 * key at alias `nearyou_auth_tokens_master_key`. The encryption substrate replaces the
 * deprecated `androidx.security.crypto.EncryptedSharedPreferences` (deprecated as of
 * `androidx.security:security-crypto:1.1.0-alpha07`) per `design.md` Decision 3 + the
 * official [Android Developers reference](https://developer.android.com/reference/androidx/security/crypto/EncryptedSharedPreferences).
 *
 * No `setUserAuthenticationRequired(true)` is set on the master key — locking the keyset
 * behind biometric/lockscreen would break the post-reboot RootRouterScreen routing path.
 *
 * Threading: every entry point hops to `Dispatchers.IO` — the first AEAD dereference does
 * Keystore IPC + keyset-SharedPreferences I/O (tens–hundreds of ms on first launch), and all
 * commonMain callers arrive on `viewModelScope` (Main.immediate). The iOS actual already
 * hops to a background dispatcher; this matches it (2026-06-10 audit, 07-#4).
 *
 * Unusable-keyset recovery (write path): if the wrapped keyset can no longer be unwrapped —
 * the Keystore master key vanished (OS security reset, or pre-fix backup restore) — `read()`
 * degrades to `null` (signed out), and `write()` clears the poisoned keyset prefs and builds
 * a fresh keyset under a fresh master key instead of crashing the sign-in (07-#1 part 2;
 * old ciphertexts are unrecoverable by definition, which `read()` already treats as null).
 */
actual class SecureTokenStore(private val context: Context) : TokenStore {
    private val dataStore: DataStore<Preferences> = context.tokenStore

    private val aeadLock = Any()
    private var cachedAead: Aead? = null

    private fun aead(): Aead =
        synchronized(aeadLock) {
            cachedAead ?: buildAead().also { cachedAead = it }
        }

    private fun buildAead(): Aead {
        AeadConfig.register()
        return AndroidKeysetManager
            .Builder()
            .withSharedPref(context, TINK_KEYSET_PREF_NAME, TINK_KEYSET_PREF_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    /** Drops the poisoned keyset prefs + cached primitive so the next [aead] builds fresh. */
    private fun resetKeyset() {
        synchronized(aeadLock) {
            cachedAead = null
            @Suppress("ApplySharedPref") // recovery must be durable before the rebuild
            context
                .getSharedPreferences(TINK_KEYSET_PREF_FILE, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    actual override suspend fun read(): TokenPair? =
        withContext(Dispatchers.IO) {
            val prefs =
                try {
                    dataStore.data.first()
                } catch (_: IOException) {
                    // Unreadable store = signed out; never crash the cold-start router (07-#3).
                    return@withContext null
                }
            val encryptedAccess = prefs[ACCESS_TOKEN_KEY] ?: return@withContext null
            val encryptedRefresh = prefs[REFRESH_TOKEN_KEY] ?: return@withContext null
            val expiresAt = prefs[EXPIRES_AT_KEY] ?: return@withContext null

            try {
                val accessToken = aead().decrypt(Base64.decode(encryptedAccess, Base64.NO_WRAP), null).decodeToString()
                val refreshToken = aead().decrypt(Base64.decode(encryptedRefresh, Base64.NO_WRAP), null).decodeToString()
                TokenPair(accessToken, refreshToken, expiresAt)
            } catch (_: GeneralSecurityException) {
                // Covers both an undecryptable ciphertext AND a keyset that can't be
                // unwrapped (missing master key) — deliberately NOT resetting the keyset
                // here: Keystore is unavailable before the first post-boot unlock, and
                // destroying a valid keyset on a transient failure would sign the user
                // out for real. Recovery belongs to write(), which only runs unlocked.
                null
            }
        }

    actual override suspend fun write(tokens: TokenPair) =
        withContext(Dispatchers.IO) {
            val aead =
                try {
                    aead()
                } catch (_: GeneralSecurityException) {
                    // Poisoned keyset (master key gone). Rebuild fresh; if even the fresh
                    // build fails, Keystore itself is broken — skip the persist (tokens
                    // stay usable in-memory for this session) rather than crash sign-in.
                    resetKeyset()
                    try {
                        aead()
                    } catch (second: GeneralSecurityException) {
                        Log.w(TAG, "token persist skipped: keyset rebuild failed", second)
                        return@withContext
                    }
                }
            val encryptedAccess =
                Base64.encodeToString(
                    aead.encrypt(tokens.accessToken.encodeToByteArray(), null),
                    Base64.NO_WRAP,
                )
            val encryptedRefresh =
                Base64.encodeToString(
                    aead.encrypt(tokens.refreshToken.encodeToByteArray(), null),
                    Base64.NO_WRAP,
                )
            dataStore.edit { prefs ->
                prefs[ACCESS_TOKEN_KEY] = encryptedAccess
                prefs[REFRESH_TOKEN_KEY] = encryptedRefresh
                prefs[EXPIRES_AT_KEY] = tokens.accessExpiresAtEpochMillis
            }
            Unit
        }

    actual override suspend fun clear() =
        withContext(Dispatchers.IO) {
            dataStore.edit { it.clear() }
            Unit
        }
}
