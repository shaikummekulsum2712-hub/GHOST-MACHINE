package com.example.ghostmachine

import android.content.Context
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

    fun planCommand(
        context: Context,
        command: String,
        replyLanguage: String,
        currentState: String = "",
        lastAction: String? = null
    ): PlanResult {
        return try {
            val json = JSONObject().apply {
                put("command", command)
                put("reply_language", replyLanguage)
                put("current_state", currentState)
                if (!lastAction.isNullOrBlank()) put("last_action", lastAction)
            }

            val request = Request.Builder()
                .url("${BackendConfig.baseUrl(context)}/plan-command")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string()
                if (!response.isSuccessful || bodyText.isNullOrBlank()) {
                    Log.e(TAG, "Planner error ${response.code}: $bodyText")
                    return PlanResult.Failure
                }

                val stepsArray = JSONObject(bodyText).optJSONArray("steps")
                    ?: return PlanResult.Failure

                val steps = mutableListOf<PlannedStep>()
                for (i in 0 until stepsArray.length()) {
                    val item = stepsArray.optJSONObject(i) ?: continue
                    val intent = item.optString("intent").trim()
                    val target = item.optString("target").trim()
                    if (intent.isNotBlank()) steps += PlannedStep(intent, target)
                }

                if (steps.isEmpty()) PlanResult.Failure else PlanResult.Success(steps)
            }
        } catch (e: Exception) {
            Log.e(TAG, "planCommand failed", e)
            PlanResult.Failure
        }
    }

    fun analyzeScreen(
        context: Context,
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
            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("command", command)
                .addFormDataPart("screen_elements_json", screenElementsJson)
                .addFormDataPart("parsed_intent", parsedIntent)
                .addFormDataPart("parsed_target", parsedTarget)
                .addFormDataPart("android_uncertainty", androidUncertainty)
                .addFormDataPart("reply_language", replyLanguage)
                .addFormDataPart(
                    "screenshot",
                    "screen.jpg",
                    screenshotBytes.toRequestBody("image/jpeg".toMediaType())
                )

            if (!previousAction.isNullOrBlank()) {
                bodyBuilder.addFormDataPart("previous_action", previousAction)
            }

            val request = Request.Builder()
                .url("${BackendConfig.baseUrl(context)}/analyze-screen")
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
