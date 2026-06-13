package id.nearyou.app.config

import id.nearyou.app.BuildConfig

/**
 * Android resolves the Supabase config from the per-flavor `BuildConfig.SUPABASE_URL` /
 * `BuildConfig.SUPABASE_ANON_KEY` fields declared in `mobile/app/build.gradle.kts`.
 */
actual val supabaseConfig: SupabaseRealtimeConfig
    get() = SupabaseRealtimeConfig(url = BuildConfig.SUPABASE_URL, anonKey = BuildConfig.SUPABASE_ANON_KEY)
