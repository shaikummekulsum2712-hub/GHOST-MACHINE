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

    // Phone -> laptop, over wifi. Must be the laptop's LAN IP, not 127.0.0.1
    // (127.0.0.1 on the phone points back at the phone itself).
    // Also make sure the backend is started with `--host 0.0.0.0`, or it will
    // only accept connections from the laptop itself even with the right IP here.
    private const val BASE_URL = "http://192.168.1.8:8000"

    fun analyzeScreen(
        command: String,
        screenshotBytes: ByteArray,
        screenElementsJson: String,
        parsedIntent: String,
        parsedTarget: String,
        androidUncertainty: String,
        previousAction: String?,
        replyLanguage: String
    ): String? {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

            val screenshotBody = screenshotBytes.toRequestBody("image/jpeg".toMediaType())

            val multipartBodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("command", command)
                .addFormDataPart("screen_elements_json", screenElementsJson)
                .addFormDataPart("parsed_intent", parsedIntent)
                .addFormDataPart("parsed_target", parsedTarget)
                .addFormDataPart("android_uncertainty", androidUncertainty)
                .addFormDataPart("reply_language", replyLanguage)
                .addFormDataPart("screenshot", "screen.jpg", screenshotBody)

            if (!previousAction.isNullOrBlank()) {
                multipartBodyBuilder.addFormDataPart("previous_action", previousAction)
            }

            val request = Request.Builder()
                .url("$BASE_URL/analyze-screen")
                .post(multipartBodyBuilder.build())
                .build()

            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Backend error: ${response.code} $bodyText")
                    return null
                }

                bodyText
            }
        } catch (e: Exception) {
            Log.e(TAG, "analyzeScreen failed", e)
            null

        }
    }
}