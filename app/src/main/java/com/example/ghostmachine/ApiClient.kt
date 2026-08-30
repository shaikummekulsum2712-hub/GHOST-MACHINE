package com.example.ghostmachine

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val TAG = "ApiClient"

    // Phone -> laptop, over wifi. Must be the laptop's LAN IP, not 127.0.0.1.
    // Backend must be started with --host 0.0.0.0.
    private const val BASE_URL = "http://10.210.137.54:8000"

    sealed class AnalyzeResult {
        data class Success(val json: String) : AnalyzeResult()
        data class ServerError(val code: Int, val body: String?) : AnalyzeResult()
        data class NetworkError(val message: String) : AnalyzeResult()
    }

    data class PlannedStep(val intent: String, val target: String)

    sealed class PlanResult {
        data class Success(val steps: List<PlannedStep>) : PlanResult()
        object Failure : PlanResult()
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun planCommand(command: String, replyLanguage: String): PlanResult {
        return try {
            val json = JSONObject().apply {
                put("command", command)
                put("reply_language", replyLanguage)
            }

            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$BASE_URL/plan-command")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return PlanResult.Failure
                val bodyText = response.body?.string() ?: return PlanResult.Failure

                val obj = JSONObject(bodyText)
                val stepsArray = obj.getJSONArray("steps")
                val steps = mutableListOf<PlannedStep>()
                for (i in 0 until stepsArray.length()) {
                    val stepObj = stepsArray.getJSONObject(i)
                    steps.add(PlannedStep(stepObj.getString("intent"), stepObj.getString("target")))
                }
                PlanResult.Success(steps)
            }
        } catch (e: Exception) {
            Log.e(TAG, "planCommand failed", e)
            PlanResult.Failure
        }
    }
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