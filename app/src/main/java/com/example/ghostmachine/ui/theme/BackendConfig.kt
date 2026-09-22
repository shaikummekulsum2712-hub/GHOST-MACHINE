package com.example.ghostmachine

import android.content.Context
import android.net.Uri

/**
 * Stores the address of the FastAPI server on the demo network.
 *
 * The backend address can be changed without rebuilding the APK.
 */
object BackendConfig {
    private const val PREFS = "ghost_machine_settings"
    private const val BASE_URL_KEY = "backend_base_url"

    const val DEFAULT_BASE_URL = "http://192.168.1.7:8000"

    fun baseUrl(context: Context): String {
        val saved = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(BASE_URL_KEY, null)
            ?.trim()
            ?.trimEnd('/')

        return if (isValidBaseUrl(saved)) {
            saved!!
        } else {
            DEFAULT_BASE_URL
        }
    }

    fun saveBaseUrl(context: Context, value: String): Boolean {
        val normalized = value.trim().trimEnd('/')

        if (!isValidBaseUrl(normalized)) {
            return false
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(BASE_URL_KEY, normalized)
            .apply()

        return true
    }

    private fun isValidBaseUrl(value: String?): Boolean {
        if (value.isNullOrBlank()) return false

        val uri = runCatching {
            Uri.parse(value)
        }.getOrNull() ?: return false

        if (uri.scheme !in setOf("http", "https")) {
            return false
        }

        if (uri.host.isNullOrBlank()) {
            return false
        }

        if (uri.port !in 1..65535) {
            return false
        }

        if (!uri.path.isNullOrBlank() && uri.path != "/") {
            return false
        }

        if (!uri.query.isNullOrBlank()) {
            return false
        }

        if (!uri.fragment.isNullOrBlank()) {
            return false
        }

        return true
    }
}