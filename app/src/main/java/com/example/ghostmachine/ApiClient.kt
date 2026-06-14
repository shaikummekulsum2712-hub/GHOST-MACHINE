package com.example.ghostmachine

import android.util.Log
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val BASE_URL = "http://127.0.0.1:8000"

    fun analyzeScreen(
        command: String,
        screenshotBytes: ByteArray,
        screenElementsJson: String,
        parsedIntent: String,
        parsedTarget: String,
        androidUncertainty: String,
        previousAction: String?
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
                    Log.e("ApiClient", "Backend error: ${response.code} $bodyText")
                    return null
                }
                bodyText
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "analyzeScreen failed", e)
            null
        }
    }

}