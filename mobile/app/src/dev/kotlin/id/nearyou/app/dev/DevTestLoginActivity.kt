package id.nearyou.app.dev

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
 * DEV-FLAVOR ONLY. This file lives in `src/dev/` and is compiled **only** into the `dev`
 * variant — it is physically absent from the staging/production APKs (verify with
 * `aapt dump xmltree <staging>.apk AndroidManifest.xml | grep DevTestLogin` → empty).
 *
 * Test-login auth bypass for the Maestro E2E harness. Social login (Google/Apple) can't be
 * driven by automation (the provider UI leaves the app sandbox + blocks bots), so instead a
 * deep link injects a pre-minted, server-signed session straight into [TokenStore]; once a
 * token is present `AuthFlow.isAuthenticated()` is true and `RootRouterScreen` routes past
 * Sign-In. Tokens are produced offline by `dev/scripts/seed-test-user.sh` +
 * `dev/scripts/mint-dev-jwt.sh` (backend is never modified — zero production attack surface).
 *
 *   adb shell am start -a android.intent.action.VIEW \
 *     -d "nearyou-dev://test-login?access=<jwt>&refresh=<jwt>&exp=<epochMillis>" id.nearyou.app.dev
 */
class DevTestLoginActivity : Activity() {
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
