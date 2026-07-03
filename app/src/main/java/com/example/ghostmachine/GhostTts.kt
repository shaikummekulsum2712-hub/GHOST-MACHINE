package com.example.ghostmachine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class GhostTts(private val context: Context) {

    companion object {
        private const val TAG = "GhostTts"
    }

    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    fun init() {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS

            if (!ready) {
                Log.e(TAG, "TTS init failed")
            }
        }
    }

    fun speak(text: String, language: String) {
        if (!ready || text.isBlank()) return

        val locale = when (language) {
            "hinglish" -> Locale("hi", "IN")
            "telugu" -> Locale("te", "IN")
            else -> Locale.US
        }

        try {
            val result = tts?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not available on this device: $locale, falling back to US English")
                tts?.setLanguage(Locale.US)
            }

            tts?.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "ghost_tts_${System.currentTimeMillis()}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Speak failed", e)
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
    }
}