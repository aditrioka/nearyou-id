package id.nearyou.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import id.nearyou.app.auth.CurrentActivityHolder
import id.nearyou.app.di.initKoin
import id.nearyou.app.location.LocationPermissionRequestBridge
import org.koin.android.ext.koin.androidContext
import org.koin.mp.KoinPlatformTools

class MainActivity : ComponentActivity() {
    private val activityHolder: CurrentActivityHolder
        get() = KoinPlatformTools.defaultContext().get().get()

    private val locationPermissionBridge: LocationPermissionRequestBridge
        get() = KoinPlatformTools.defaultContext().get().get()

    // Registered as a field initializer (before STARTED, as ActivityResultContracts requires); the
    // coarse-location grant result is routed back to the suspend request via the Koin-singleton bridge
    // (mobile-location-permission-flow). The bridge is resolved lazily inside the callback, so it is
    // safe to register before initKoin runs in onCreate.
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            locationPermissionBridge.onResult(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Supply the application Context so the Android platform Koin module can build
        // SecureTokenStore. initKoin is idempotent — safe across activity recreation.
        initKoin {
            androidContext(this@MainActivity.applicationContext)
        }

        // Hand the registered launcher to the permission bridge (the Activity-result seam the
        // AndroidLocationPermissionController drives).
        locationPermissionBridge.launcher = locationPermissionLauncher

        setContent {
            App()
        }
    }

    override fun onResume() {
        super.onResume()
        // Credential Manager needs a foreground Activity to present the account picker.
        activityHolder.activity = this
    }

    override fun onPause() {
        // Clear the reference so it never outlives the foreground window.
        if (activityHolder.activity === this) {
            activityHolder.activity = null
        }
        super.onPause()
    }

    override fun onDestroy() {
        // The Koin-singleton bridge otherwise keeps the launcher → callback → this Activity
        // chain reachable after destroy (2026-06-10 audit, 07-#6). Identity-guarded so a
        // recreated Activity's fresher launcher is never clobbered by the old one's teardown.
        if (locationPermissionBridge.launcher === locationPermissionLauncher) {
            locationPermissionBridge.launcher = null
        }
        super.onDestroy()
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
