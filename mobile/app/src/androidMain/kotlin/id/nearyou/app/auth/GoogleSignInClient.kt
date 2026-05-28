package id.nearyou.app.auth

import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Android `GoogleSignInClient` actual: Credential Manager API.
 *
 * Uses `androidx.credentials.CredentialManager` + the `GetGoogleIdOption` helper per
 * `openspec/specs/mobile-auth-signin/spec.md` § "Android actual uses Credential Manager".
 * The deprecated `com.google.android.gms.auth.api.signin.GoogleSignInClient` legacy path
 * is intentionally NOT referenced.
 *
 * @param activityHolder supplies the foreground Activity context Credential Manager needs
 *   to present the account-picker UI.
 * @param serverClientId the backend's Google OAuth **server** (web) client ID — set as the
 *   audience of the returned Google ID token so the backend `/signin` endpoint can verify
 *   `aud`. Injected from `BuildConfig.GOOGLE_SERVER_CLIENT_ID` (per-flavor) via Koin.
 */
actual class GoogleSignInClient(
    private val activityHolder: CurrentActivityHolder,
    private val serverClientId: String,
) : GoogleSignInGateway {
    actual override suspend fun signIn(): GoogleSignInResult {
        val activity =
            activityHolder.activity
                ?: return GoogleSignInResult.Failed("No foreground activity available for Google Sign-In")

        return try {
            val credentialManager = CredentialManager.create(activity)
            val googleIdOption =
                GetGoogleIdOption.Builder()
                    .setServerClientId(serverClientId)
                    // false ⇒ show ALL Google accounts, not just previously-authorized ones
                    // (first sign-in on a fresh install has no authorized accounts yet).
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build()
            val request =
                GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
            val response = credentialManager.getCredential(activity, request)
            mapResponse(response)
        } catch (_: GetCredentialCancellationException) {
            GoogleSignInResult.UserCancelled
        } catch (e: GetCredentialException) {
            GoogleSignInResult.Failed(e.message ?: "Credential Manager sign-in failed (${e::class.simpleName})")
        }
    }

    private fun mapResponse(response: GetCredentialResponse): GoogleSignInResult {
        val credential = response.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                GoogleSignInResult.Success(
                    idToken = googleIdTokenCredential.idToken,
                    displayName = googleIdTokenCredential.displayName,
                    // GoogleIdTokenCredential.id is the account's email address.
                    email = googleIdTokenCredential.id,
                )
            } catch (e: Exception) {
                GoogleSignInResult.Failed("Failed to parse Google ID token credential: ${e.message}")
            }
        }
        return GoogleSignInResult.Failed("Unexpected credential type: ${credential.type}")
    }
}
