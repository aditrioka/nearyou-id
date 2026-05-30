package id.nearyou.app.auth

import android.app.Activity

/**
 * Holds a weakly-scoped reference to the current foreground [Activity] so the Android
 * [GoogleSignInClient] actual can pass an Activity context to Credential Manager (which
 * needs an Activity to present the account-picker UI; an application Context will not
 * display the sheet).
 *
 * `MainActivity` sets [activity] in `onResume` and clears it in `onPause` so the reference
 * never outlives the foreground window. Registered as a Koin singleton in the Android
 * platform module.
 */
class CurrentActivityHolder {
    @Volatile
    var activity: Activity? = null
}
