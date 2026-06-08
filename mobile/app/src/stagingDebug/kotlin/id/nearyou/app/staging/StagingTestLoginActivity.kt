package id.nearyou.app.staging

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import id.nearyou.app.MainActivity
import id.nearyou.app.auth.TokenPair
import id.nearyou.app.auth.TokenStore
import id.nearyou.app.di.initKoin
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.mp.KoinPlatformTools

/**
 * STAGING-DEBUG ONLY. This file lives in `src/stagingDebug/` and is compiled **only** into the
 * `stagingDebug` variant — it is physically absent from `stagingRelease` and from every
 * production APK (verify with
 * `aapt dump xmltree <production>.apk AndroidManifest.xml | grep StagingTestLogin` → empty).
 * Build-type scoping (vs the dev mechanism's flavor-wide `src/dev/`) is deliberate: `staging`
 * talks to the internet-facing `api-staging.nearyou.id`, so the exported bypass is kept out of
 * any staging *release* artifact that might get wider distribution. The `TestLoginIsolationRule`
 * Detekt rule fails the build if a `*TestLogin*` symbol is ever declared outside `src/dev/` /
 * `src/stagingDebug/`. Operator sign-off + full threat model:
 * `mobile/app/maestro/PHASE-3-staging-test-login.md`.
 *
 * Test-login auth bypass for reaching the authenticated staging Home on a real device (and in
 * Maestro E2E) without interactive Google sign-in. A deep link injects a pre-minted session
 * straight into [TokenStore]; once a token is present `AuthFlow.isAuthenticated()` is true and
 * `RootRouterScreen` routes past Sign-In.
 *
 * **The crux vs dev:** dev talks to a local backend that accepts any dummy JWT; STAGING verifies
 * the bearer JWT's RSA signature on every request (`auth/AuthPlugin.kt`). So the injected token
 * MUST be validly signed by the staging key with the exact claim set the backend expects
 * (`sub` = a seeded staging `users.id`, matching `token_version`, unexpired). Mint it with
 * `dev/scripts/mint-staging-jwt.sh` (pulls `staging-ktor-rsa-private-key` from GCP Secret Manager
 * and reuses the real `JwtIssuer`, so the claims never drift). The deep link only WRITES the
 * token — the backend signature check is the real gate.
 *
 *   adb shell am start -a android.intent.action.VIEW \
 *     -d "nearyou-staging://test-login?access=<jwt>&refresh=<jwt>&exp=<epochMillis>" id.nearyou.app.staging
 *
 * Mirrors `DevTestLoginActivity` (src/dev); nav-library-agnostic (depends only on token presence
 * in [TokenStore] + [MainActivity]).
 */
class StagingTestLoginActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // initKoin is idempotent — safe whether or not MainActivity has run yet.
        initKoin { androidContext(applicationContext) }

        val access = intent?.data?.getQueryParameter("access")
        if (access != null) {
            val refresh = intent?.data?.getQueryParameter("refresh") ?: access
            val exp =
                intent?.data?.getQueryParameter("exp")?.toLongOrNull()
                    ?: (System.currentTimeMillis() + FALLBACK_TTL_MS)
            val tokenStore: TokenStore = KoinPlatformTools.defaultContext().get().get()
            runBlocking { tokenStore.write(TokenPair(access, refresh, exp)) }
        }

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
        )
        finish()
    }

    private companion object {
        const val FALLBACK_TTL_MS = 15 * 60 * 1000L
    }
}
