package id.nearyou.app.auth

import cocoapods.GoogleSignIn.GIDSignIn
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import kotlin.coroutines.resume

// GIDSignInErrorCode.canceled == -5 (kGIDSignInErrorDomain). The numeric value is stable
// across GoogleSignIn 6.x/7.x/8.x; we compare numerically because the K/N cinterop binding
// for the enum case name varies by SDK header layout.
private const val GID_SIGN_IN_ERROR_CANCELED: Long = -5

// kGIDSignInErrorDomain's value (GIDSignIn.h). Compared as a literal because the cinterop
// binding for extern NSErrorDomain constants varies by SDK header layout; the string is
// API-stable across GoogleSignIn 6.x/7.x/8.x. Without the domain check, ANY NSError with
// code -5 (e.g. a network-layer error surfaced through the completion) would be
// misclassified as a silent user-cancel (2026-06-10 audit, 07-#12).
private const val GID_SIGN_IN_ERROR_DOMAIN: String = "com.google.GIDSignIn"

/**
 * iOS `GoogleSignInClient` actual: Google Sign-In iOS SDK.
 *
 * Calls `GIDSignIn.sharedInstance.signIn(withPresenting:)` on the top view controller and
 * wraps the completion handler in `suspendCancellableCoroutine` per `design.md` Decision 2.
 * The `GoogleSignIn` Pod is consumed via the KMP `kotlin("native.cocoapods")` plugin. No
 * `WKWebView` / `SFSafariViewController` fallback per spec § "iOS actual uses GoogleSignIn SDK".
 *
 * GoogleSignIn auto-configures from `Info.plist` (the canonical mechanism per
 * developers.google.com/identity/sign-in/ios): `GIDClientID` (the iOS OAuth client) and
 * `GIDServerClientID` (the backend/web client — makes the issued ID token's `aud` the backend
 * client that `POST /api/v1/auth/signin` validates against `GOOGLE_CLIENT_IDS`). Both are driven
 * per-environment by the active xcconfig ($(GID_CLIENT_ID) / $(GID_SERVER_CLIENT_ID)). The
 * OAuth-callback URL scheme is the iOS client's `REVERSED_CLIENT_ID` (`Info.plist`
 * `CFBundleURLTypes`, via `$(GID_REVERSED_CLIENT_ID)`), routed back via the `iOSApp.swift`
 * `.onOpenURL { GIDSignIn.sharedInstance.handle(it) }` hook. See §7.
 */
@OptIn(ExperimentalForeignApi::class)
actual class GoogleSignInClient : GoogleSignInGateway {
    // Main-confined (2026-06-10 audit, 07-#7): GIDSignIn presents UIKit UI and
    // topViewController() walks UIApplication.windows — both main-thread-only API. Works
    // today only because all callers are viewModelScope (Main); the wrap makes the
    // requirement structural so an innocent dispatcher refactor upstream can't break it.
    actual override suspend fun signIn(): GoogleSignInResult =
        withContext(Dispatchers.Main) {
            signInOnMain()
        }

    private suspend fun signInOnMain(): GoogleSignInResult =
        suspendCancellableCoroutine { continuation ->
            val presenter = topViewController()
            if (presenter == null) {
                continuation.resume(GoogleSignInResult.Failed("No presenting view controller for Google Sign-In"))
                return@suspendCancellableCoroutine
            }

            GIDSignIn.sharedInstance.signInWithPresentingViewController(presenter) { signInResult, error ->
                when {
                    error != null -> {
                        if (error.domain == GID_SIGN_IN_ERROR_DOMAIN && error.code == GID_SIGN_IN_ERROR_CANCELED) {
                            continuation.resume(GoogleSignInResult.UserCancelled)
                        } else {
                            continuation.resume(
                                GoogleSignInResult.Failed(error.localizedDescription),
                            )
                        }
                    }

                    signInResult != null -> {
                        val user = signInResult.user
                        val idToken = user.idToken?.tokenString
                        if (idToken == null) {
                            continuation.resume(GoogleSignInResult.Failed("Google Sign-In returned no ID token"))
                        } else {
                            continuation.resume(
                                GoogleSignInResult.Success(
                                    idToken = idToken,
                                    displayName = user.profile?.name,
                                    email = user.profile?.email,
                                ),
                            )
                        }
                    }

                    else -> continuation.resume(GoogleSignInResult.Failed("Google Sign-In returned no result"))
                }
            }
        }
}

/**
 * Walk from the key window's root view controller down the presented-VC chain to find the
 * topmost presenter for the Google Sign-In sheet.
 */
@OptIn(ExperimentalForeignApi::class)
private fun topViewController(): UIViewController? {
    val windows = UIApplication.sharedApplication.windows
    val keyWindow =
        windows.firstOrNull { (it as? UIWindow)?.isKeyWindow() == true } as? UIWindow
            ?: windows.firstOrNull() as? UIWindow
    var top = keyWindow?.rootViewController
    while (top?.presentedViewController != null) {
        top = top.presentedViewController
    }
    return top
}
