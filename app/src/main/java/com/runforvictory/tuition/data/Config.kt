package com.runforvictory.tuition.data

import com.runforvictory.tuition.BuildConfig

object AppConfig {
    val supabaseUrl: String get() = BuildConfig.SUPABASE_URL
    val supabaseAnonKey: String get() = BuildConfig.SUPABASE_ANON_KEY
    val mapsApiKey: String get() = BuildConfig.MAPS_API_KEY
    val environment: String get() = BuildConfig.ENVIRONMENT
    val isDebug: Boolean get() = BuildConfig.DEBUG
}
