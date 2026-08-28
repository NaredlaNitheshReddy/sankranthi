package com.example.sankranthi.data

import com.example.sankranthi.BuildConfig

/**
 * Backend configuration, supplied through `local.properties` at build time.
 *
 * When Supabase credentials are missing the app runs against an in-memory demo
 * backend instead of failing to start, so a fresh clone is usable immediately.
 */
object AppConfig {
    val supabaseUrl: String = BuildConfig.SUPABASE_URL
    val supabaseAnonKey: String = BuildConfig.SUPABASE_ANON_KEY
    val googleWebClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID

    val hasSupabase: Boolean = supabaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()

    /** Google sign-in needs both Supabase and an OAuth web client id. */
    val hasGoogleSignIn: Boolean = hasSupabase && googleWebClientId.isNotBlank()
}
