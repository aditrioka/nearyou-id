package id.nearyou.app.auth

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

private const val SERVICE = "id.nearyou.app.auth"
private const val ACCOUNT = "tokens"

@Serializable
private data class StoredTokenPayload(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtEpochMillis: Long,
)

/**
 * iOS `SecureTokenStore` actual: Keychain Services with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.
 *
 * The full `TokenPair` is JSON-encoded and stored as a single Keychain item keyed by
 * `(service = "id.nearyou.app.auth", account = "tokens")` per `design.md` Decision 3.
 *
 * Accessibility constant rationale:
 *  - `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` ⇒ token usable after device unlock,
 *    NOT synced via iCloud Keychain, NOT survivable across device transfer.
 *  - NOT `kSecAttrAccessibleAlways` (would weaken security; deprecated).
 *  - NOT `kSecAttrAccessibleAfterFirstUnlock` without the `ThisDeviceOnly` suffix (would
 *    sync via iCloud Keychain across the user's devices).
 *
 * No `kSecAttrAccessGroup` is set on the queries — single-bundle-scoped storage. A future
 * watch-app or shared-app-group target MUST NOT be able to read tokens written by this app
 * via the default keychain access group. Per `openspec/specs/mobile-auth-signin/spec.md`
 * § "iOS Keychain item is single-bundle-scoped, no kSecAttrAccessGroup set".
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class SecureTokenStore : TokenStore {
    private val json = Json { ignoreUnknownKeys = true }

    actual override suspend fun read(): TokenPair? =
        withContext(Dispatchers.Default) {
            val raw = readKeychainItem() ?: return@withContext null
            val payload =
                try {
                    json.decodeFromString(StoredTokenPayload.serializer(), raw)
                } catch (_: Throwable) {
                    return@withContext null
                }
            TokenPair(
                accessToken = payload.accessToken,
                refreshToken = payload.refreshToken,
                accessExpiresAtEpochMillis = payload.accessExpiresAtEpochMillis,
            )
        }

    actual override suspend fun write(tokens: TokenPair) {
        withContext(Dispatchers.Default) {
            val payload =
                StoredTokenPayload(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    accessExpiresAtEpochMillis = tokens.accessExpiresAtEpochMillis,
                )
            val raw = json.encodeToString(StoredTokenPayload.serializer(), payload)
            writeKeychainItem(raw)
        }
    }

    actual override suspend fun clear() {
        withContext(Dispatchers.Default) {
            deleteKeychainItem()
        }
    }

    private fun writeKeychainItem(value: String) {
        deleteKeychainItem()
        val nsValue = NSString.create(string = value)
        val data = nsValue.dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val attrs =
            cfDictionaryOf(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to SERVICE,
                kSecAttrAccount to ACCOUNT,
                kSecValueData to data,
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            )
        SecItemAdd(attrs, null)
        CFRelease(attrs)
    }

    private fun readKeychainItem(): String? =
        memScoped {
            val query =
                cfDictionaryOf(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to SERVICE,
                    kSecAttrAccount to ACCOUNT,
                    kSecReturnData to kCFBooleanTrue,
                    kSecMatchLimit to kSecMatchLimitOne,
                )
            val resultPtr = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, resultPtr.ptr)
            CFRelease(query)
            if (status != errSecSuccess) return@memScoped null
            val data = CFBridgingRelease(resultPtr.value) as? NSData ?: return@memScoped null
            NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
        }

    private fun deleteKeychainItem() {
        val query =
            cfDictionaryOf(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to SERVICE,
                kSecAttrAccount to ACCOUNT,
            )
        SecItemDelete(query)
        CFRelease(query)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun cfDictionaryOf(vararg pairs: Pair<CPointer<*>?, Any?>): CFDictionaryRef {
    val dict =
        CFDictionaryCreateMutable(
            kCFAllocatorDefault,
            pairs.size.convert(),
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )!!
    for ((k, v) in pairs) {
        val cfV: CFTypeRef? =
            when (v) {
                null -> null
                is String -> CFBridgingRetain(NSString.create(string = v))
                is NSData -> CFBridgingRetain(v)
                is CPointer<*> -> v
                else -> v as? CFTypeRef
            }
        CFDictionaryAddValue(dict, k, cfV)
    }
    return dict
}
