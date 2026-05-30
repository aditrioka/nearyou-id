package id.nearyou.app.di

import id.nearyou.app.BuildConfig
import id.nearyou.app.auth.CurrentActivityHolder
import id.nearyou.app.auth.GoogleSignInClient
import id.nearyou.app.auth.GoogleSignInGateway
import id.nearyou.app.auth.SecureTokenStore
import id.nearyou.app.auth.TokenStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module =
    module {
        single<TokenStore> { SecureTokenStore(androidContext()) }
        single { CurrentActivityHolder() }
        single<GoogleSignInGateway> {
            GoogleSignInClient(
                activityHolder = get(),
                serverClientId = BuildConfig.GOOGLE_SERVER_CLIENT_ID,
            )
        }
    }
