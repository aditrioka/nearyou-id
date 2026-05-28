package id.nearyou.app.auth

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.first
import java.security.GeneralSecurityException

private const val DATASTORE_NAME = "nearyou_auth_tokens"
private const val TINK_KEYSET_PREF_NAME = "nearyou_auth_tokens_tink_keyset"
private const val TINK_KEYSET_PREF_FILE = "nearyou_auth_tokens_tink_keyset_pref"
private const val MASTER_KEY_URI = "android-keystore://nearyou_auth_tokens_master_key"

private val ACCESS_TOKEN_KEY = stringPreferencesKey("access_token_encrypted")
private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token_encrypted")
private val EXPIRES_AT_KEY = longPreferencesKey("access_expires_at_epoch_millis")

private val Context.tokenStore: DataStore<Preferences> by preferencesDataStore(name = DATASTORE_NAME)

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
 */
actual class SecureTokenStore(private val context: Context) {
    private val dataStore: DataStore<Preferences> = context.tokenStore

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, TINK_KEYSET_PREF_NAME, TINK_KEYSET_PREF_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    actual suspend fun read(): TokenPair? {
        val prefs = dataStore.data.first()
        val encryptedAccess = prefs[ACCESS_TOKEN_KEY] ?: return null
        val encryptedRefresh = prefs[REFRESH_TOKEN_KEY] ?: return null
        val expiresAt = prefs[EXPIRES_AT_KEY] ?: return null

        return try {
            val accessToken = aead.decrypt(Base64.decode(encryptedAccess, Base64.NO_WRAP), null).decodeToString()
            val refreshToken = aead.decrypt(Base64.decode(encryptedRefresh, Base64.NO_WRAP), null).decodeToString()
            TokenPair(accessToken, refreshToken, expiresAt)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    actual suspend fun write(tokens: TokenPair) {
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
    }

    actual suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
