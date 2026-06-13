package id.nearyou.app.config

import platform.Foundation.NSBundle

/**
 * iOS resolves the Supabase config from the `SupabaseUrl` / `SupabaseAnonKey` `Info.plist` keys, driven
 * by xcconfig variables per scheme (mirroring `apiBaseUrl`). A missing key falls back to an inert
 * placeholder so a misconfigured build degrades to REST-only chat (realtime simply never connects)
 * rather than crashing — realtime is an enhancement over the always-available REST path (design D4).
 */
actual val supabaseConfig: SupabaseRealtimeConfig
    get() =
        SupabaseRealtimeConfig(
            url = (NSBundle.mainBundle.objectForInfoDictionaryKey("SupabaseUrl") as? String) ?: PLACEHOLDER_URL,
            anonKey = (NSBundle.mainBundle.objectForInfoDictionaryKey("SupabaseAnonKey") as? String) ?: PLACEHOLDER_KEY,
        )

private const val PLACEHOLDER_URL = "https://REPLACE_WITH_SUPABASE_REF.supabase.co"
private const val PLACEHOLDER_KEY = "REPLACE_WITH_SUPABASE_ANON_KEY"
