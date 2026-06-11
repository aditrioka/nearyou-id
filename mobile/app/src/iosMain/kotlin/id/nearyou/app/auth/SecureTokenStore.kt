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
import platform.Foundation.NSLog
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
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
        val status = SecItemAdd(attrs, null)
        CFRelease(attrs)
        if (status == errSecDuplicateItem) {
            // delete-then-add is not atomic: a racing write can land its add between this
            // call's delete and add. Converge by updating the existing item in place
            // (2026-06-10 audit, finding 07-#2 — the errSec contract's update path).
            val query =
                cfDictionaryOf(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to SERVICE,
                    kSecAttrAccount to ACCOUNT,
                )
            val update = cfDictionaryOf(kSecValueData to data)
            val updateStatus = SecItemUpdate(query, update)
            CFRelease(query)
            CFRelease(update)
            if (updateStatus != errSecSuccess) {
                NSLog("SecureTokenStore: keychain update fallback failed, OSStatus=%d", updateStatus)
            }
        } else if (status != errSecSuccess) {
            // A silently-dropped write left the user "mysteriously signed out" on the
            // next cold start with zero diagnostics (2026-06-10 audit, finding 07-#2).
            // Surface the OSStatus only — NEVER token material. Other statuses
            // (interaction-not-allowed, IO) are environmental and visible in the
            // device console via this line.
            NSLog("SecureTokenStore: keychain write failed, OSStatus=%d", status)
        }
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
        when (v) {
            null -> Unit
            // `CFBridgingRetain` returns a +1-owned ref; `CFDictionaryAddValue` retains it
            // again (kCFTypeDictionaryValueCallBacks), so we MUST release our +1 after adding
            // — otherwise each String / NSData leaks on every read/write/clear. The dict's
            // own retain keeps the value alive for the dict's lifetime.
            is String -> {
                val cfStr = CFBridgingRetain(NSString.create(string = v))
                CFDictionaryAddValue(dict, k, cfStr)
                CFRelease(cfStr)
            }
            is NSData -> {
                val cfData = CFBridgingRetain(v)
                CFDictionaryAddValue(dict, k, cfData)
                CFRelease(cfData)
            }
            // CF constants (kSecClassGenericPassword, kSecMatchLimitOne, kCFBooleanTrue, …):
            // singletons we do NOT own — add without retaining/releasing.
            is CPointer<*> -> CFDictionaryAddValue(dict, k, v)
            else -> CFDictionaryAddValue(dict, k, v as? CFTypeRef)
        }
    }
    return dict
}
