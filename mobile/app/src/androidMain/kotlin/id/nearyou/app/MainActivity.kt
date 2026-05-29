package id.nearyou.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import id.nearyou.app.auth.CurrentActivityHolder
import id.nearyou.app.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.mp.KoinPlatformTools

class MainActivity : ComponentActivity() {
    private val activityHolder: CurrentActivityHolder
        get() = KoinPlatformTools.defaultContext().get().get()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Supply the application Context so the Android platform Koin module can build
        // SecureTokenStore. initKoin is idempotent — safe across activity recreation.
        initKoin {
            androidContext(this@MainActivity.applicationContext)
        }

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
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
