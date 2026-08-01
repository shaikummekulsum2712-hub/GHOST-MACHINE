package com.example.ghostmachine

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val TAG = "ApiClient"

    // Phone -> laptop, over wifi. Must be the laptop's LAN IP, not 127.0.0.1.
    // Backend must be started with --host 0.0.0.0.
    private const val BASE_URL = "http://192.168.1.7:8000"

    sealed class AnalyzeResult {
        data class Success(val json: String) : AnalyzeResult()
        data class ServerError(val code: Int, val body: String?) : AnalyzeResult()
        data class NetworkError(val message: String) : AnalyzeResult()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun analyzeScreen(
        command: String,
        screenshotBytes: ByteArray,
        screenElementsJson: String,
        parsedIntent: String,
        parsedTarget: String,
        androidUncertainty: String,
        previousAction: String?,
        replyLanguage: String
    ): AnalyzeResult {
        return try {
            val screenshotBody = screenshotBytes.toRequestBody("image/jpeg".toMediaType())

            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("command", command)
                .addFormDataPart("screen_elements_json", screenElementsJson)
                .addFormDataPart("parsed_intent", parsedIntent)
                .addFormDataPart("parsed_target", parsedTarget)
                .addFormDataPart("android_uncertainty", androidUncertainty)
                .addFormDataPart("reply_language", replyLanguage)
                .addFormDataPart("screenshot", "screen.jpg", screenshotBody)

            if (!previousAction.isNullOrBlank()) {
                bodyBuilder.addFormDataPart("previous_action", previousAction)
            }

            val request = Request.Builder()
                .url("$BASE_URL/analyze-screen")
                .post(bodyBuilder.build())
                .build()

            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string()
                when {
                    !response.isSuccessful -> {
                        Log.e(TAG, "Backend error: ${response.code} $bodyText")
                        AnalyzeResult.ServerError(response.code, bodyText)
                    }
                    bodyText.isNullOrBlank() -> {
                        Log.e(TAG, "Backend returned empty body")
                        AnalyzeResult.ServerError(response.code, "empty body")
                    }
                    else -> AnalyzeResult.Success(bodyText)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "analyzeScreen failed", e)
            AnalyzeResult.NetworkError(e.message ?: "unknown network error")
        }
    }
}