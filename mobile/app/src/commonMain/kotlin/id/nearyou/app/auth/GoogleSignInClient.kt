package id.nearyou.app.auth

/**
 * The Google Sign-In ceremony contract that `AuthRepository` depends on. The production
 * binding is the platform [GoogleSignInClient] expect/actual; commonTest substitutes a
 * `FakeGoogleSignInGateway` so the repository's outcome-mapping logic can be exercised
 * without a real platform SDK (per `tasks.md` 4.4 + 4.5).
 */
interface GoogleSignInGateway {
    suspend fun signIn(): GoogleSignInResult
}

/**
 * Coroutine-suspending Google Sign-In ceremony wrapper.
 *
 * Platform actuals:
 *  - androidMain: Credential Manager API (`androidx.credentials.CredentialManager` +
 *    `com.google.android.libraries.identity.googleid.GetGoogleIdOption`). Per
 *    `openspec/specs/mobile-auth-signin/spec.md` § "Android actual uses Credential
 *    Manager" — the deprecated `com.google.android.gms.auth.api.signin.GoogleSignInClient`
 *    is forbidden.
 *  - iosMain: Google Sign-In iOS SDK (`GIDSignIn.sharedInstance.signIn(withPresenting:)`),
 *    consumed via the KMP `kotlin("native.cocoapods")` plugin (`pod("GoogleSignIn")`).
 *    No `WKWebView` / `SFSafariViewController` fallback — webview-based OAuth is forbidden.
 *
 * The exposed surface (suspend `signIn(): GoogleSignInResult`) is intentionally minimal so
 * `AuthRepository` consumes the ceremony uniformly across platforms via [GoogleSignInGateway].
 */
expect class GoogleSignInClient : GoogleSignInGateway {
    override suspend fun signIn(): GoogleSignInResult
}

/**
 * Sealed result type for the Google Sign-In ceremony. Three variants per `design.md`
 * Decision 2:
 *
 *  - [Success] — ceremony completed; carries the Google ID token (the audience for the
 *    backend `/signin` exchange) plus optional display name + email (used only as the
 *    backend request body; never rendered to user-facing error UI per spec § "No
 *    error-state UI renders Google email or displayName").
 *  - [UserCancelled] — user dismissed the Google Sign-In sheet. NOT an error; the
 *    SignInScreen returns to its initial CTA-visible state.
 *  - [Failed] — ceremony failed for any other reason. The `message` payload is intended
 *    for Sentry / OTel logs ONLY — it MUST NOT surface in user-facing UI.
 */
sealed interface GoogleSignInResult {
    data class Success(
        val idToken: String,
        val displayName: String?,
        val email: String?,
    ) : GoogleSignInResult

    data object UserCancelled : GoogleSignInResult

    data class Failed(val message: String) : GoogleSignInResult
}
