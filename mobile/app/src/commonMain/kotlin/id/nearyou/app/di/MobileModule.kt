package id.nearyou.app.di

import id.nearyou.app.auth.AuthApiClient
import id.nearyou.app.auth.AuthFlow
import id.nearyou.app.auth.AuthRepository
import id.nearyou.app.auth.SessionInvalidator
import id.nearyou.app.config.apiBaseUrl
import id.nearyou.app.config.httpClientEngine
import id.nearyou.app.config.isDebugBuild
import id.nearyou.app.network.HttpClientFactory
import org.koin.dsl.module

/**
 * Cross-platform Koin bindings for `:mobile:app`. Platform-specific bindings
 * (`SecureTokenStore`, `GoogleSignInGateway`, the Android `CurrentActivityHolder`) live in
 * [platformModule]; both are loaded in [initKoin].
 *
 * Mobile #3 registers: `SessionInvalidator`, the shared `HttpClient`, and `AuthApiClient`.
 * `AuthRepository` is registered here in §6.
 */
val mobileModule =
    module {
        single { SessionInvalidator(get()) }
        single {
            HttpClientFactory.create(
                apiBaseUrl = apiBaseUrl,
                tokenStore = get(),
                sessionInvalidator = get(),
                engine = httpClientEngine(),
                installLogging = isDebugBuild,
            )
        }
        single { AuthApiClient(get()) }
        single {
            AuthRepository(
                googleSignIn = get(),
                authApiClient = get(),
                tokenStore = get(),
                sessionInvalidator = get(),
            )
        }
        // Bind the AuthFlow interface to the concrete AuthRepository so screens depend on the
        // testable seam while the concrete remains resolvable.
        single<AuthFlow> { get<AuthRepository>() }
    }

/**
 * Platform-specific Koin module. Android binds `SecureTokenStore` (via `androidContext()`),
 * the `CurrentActivityHolder`, and `GoogleSignInGateway` (Credential Manager). iOS binds the
 * Keychain `SecureTokenStore` + the GoogleSignIn-SDK `GoogleSignInGateway`.
 */
expect val platformModule: org.koin.core.module.Module
