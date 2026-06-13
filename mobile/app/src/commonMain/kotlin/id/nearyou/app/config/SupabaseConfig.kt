package id.nearyou.app.config

/**
 * The NON-SECRET Supabase Realtime connection config the `:infra:supabase-realtime` subscriber needs
 * (`mobile-chat-screen` task 3.2): the project [url] + the [anonKey] (the publishable/anon key — safe in
 * a client by Supabase's design; RLS + the HS256 companion token are the security boundary). NOT the
 * service-role key (that is backend-only, in GCP Secret Manager). Resolved per flavor/scheme, NOT a
 * hardcoded literal at the call site (the `id.nearyou.app.config` carve-out is the only home for these).
 */
data class SupabaseRealtimeConfig(
    val url: String,
    val anonKey: String,
)

/**
 * Environment-aware Supabase config.
 *  - androidMain: `BuildConfig.SUPABASE_URL` / `BuildConfig.SUPABASE_ANON_KEY` per gradle product flavor
 *    (placeholders until the operator provisions the staging values — the `GOOGLE_SERVER_CLIENT_ID`
 *    precedent; overridable via `-PstagingSupabaseUrl=` / `-PstagingSupabaseAnonKey=`).
 *  - iosMain: `NSBundle` `SupabaseUrl` / `SupabaseAnonKey` Info.plist keys (xcconfig-driven).
 */
expect val supabaseConfig: SupabaseRealtimeConfig
