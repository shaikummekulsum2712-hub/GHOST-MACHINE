package com.example.ghostmachine

import android.content.Context
import android.net.Uri

/**
 * Stores the address of the FastAPI server on the demo network.  Keeping this
 * out of source code means a DHCP address change can be repaired from the app
 * before a presentation, without rebuilding and reinstalling an APK.
 */
object BackendConfig {
    private const val PREFS = "ghost_machine_settings"
    private const val BASE_URL_KEY = "backend_base_url"
    const val DEFAULT_BASE_URL = "http://172.28.16.1:8000"

    fun baseUrl(context: Context): String = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(BASE_URL_KEY, DEFAULT_BASE_URL)
        ?.trim()
        ?.trimEnd('/')
        .orEmpty()

    fun saveBaseUrl(context: Context, value: String): Boolean {
        val normalized = value.trim().trimEnd('/')
        val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return false
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.port == -1) {
            return false
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(BASE_URL_KEY, normalized)
            .apply()
        return true
    }
}
