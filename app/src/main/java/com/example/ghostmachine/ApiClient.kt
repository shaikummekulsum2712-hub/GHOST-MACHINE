package com.example.ghostmachine

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object ApiClient {
    private const val BASE_URL = "http://localhost:8000"

    /**
     * Send a command to the backend for AI processing.
     * If screenshotBase64 is provided, the backend will use Gemini's vision API
     * to analyze the actual screen content for precise coordinate generation.
     */
    fun getNextAction(command: String, screenshotBase64: String? = null): String? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/next-action")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.doInput = true
            // Increase timeouts for vision requests (screenshot upload can be large)
            connection.connectTimeout = 15000
            connection.readTimeout = 30000

            // Write JSON request body
            val requestBody = JSONObject()
            requestBody.put("command", command)
            requestBody.put("sender", "android")
            if (screenshotBase64 != null) {
                requestBody.put("screenshot", screenshotBase64)
                Log.i("ApiClient", "Sending command with screenshot (${screenshotBase64.length / 1024}KB base64)")
            } else {
                Log.i("ApiClient", "Sending command without screenshot (blind mode)")
            }

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(requestBody.toString())
            writer.flush()
            writer.close()

            // Read JSON response body
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                return response.toString()
            } else {
                Log.e("ApiClient", "Error Response Code: $responseCode")
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Failed to connect to backend", e)
        } finally {
            connection?.disconnect()
        }
        return null
    }

    fun pollCommand(): String? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/poll-command")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                return response.toString()
            }
        } catch (e: Exception) {
            // Quiet fail during background polling
        } finally {
            connection?.disconnect()
        }
        return null
    }

    /**
     * Upload a screenshot to the backend, optionally tied to a specific command ID.
     * If commandId is provided, the backend will re-generate the action plan using
     * Gemini's vision API with the screenshot.
     *
     * @return The response JSON string (may contain re-planned steps), or null on failure
     */
    fun uploadScreenshot(screenshotBase64: String, commandId: String? = null): String? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/upload-screenshot")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.doInput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 30000

            val requestBody = JSONObject()
            requestBody.put("screenshot", screenshotBase64)
            if (commandId != null) {
                requestBody.put("command_id", commandId)
            }

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(requestBody.toString())
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                Log.i("ApiClient", "Screenshot uploaded successfully")
                return response.toString()
            } else {
                Log.e("ApiClient", "Screenshot upload failed with code: $responseCode")
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Failed to upload screenshot", e)
        } finally {
            connection?.disconnect()
        }
        return null
    }

    fun reportStatus(
        id: String,
        status: String,
        currentStep: Int,
        totalSteps: Int,
        currentAction: String = "",
        currentReason: String = "",
        error: String? = null
    ): Boolean {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/report-status")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            val requestBody = JSONObject()
            requestBody.put("id", id)
            requestBody.put("status", status)
            requestBody.put("current_step", currentStep)
            requestBody.put("total_steps", totalSteps)
            requestBody.put("current_action", currentAction)
            requestBody.put("current_reason", currentReason)
            if (error != null) {
                requestBody.put("error", error)
            } else {
                requestBody.put("error", JSONObject.NULL)
            }

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(requestBody.toString())
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            return responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.e("ApiClient", "Failed to report status to backend", e)
        } finally {
            connection?.disconnect()
        }
        return false
    }

    // ══════════════════════════════════════════════════════════
    //  VISION LOOP API METHODS
    // ══════════════════════════════════════════════════════════

    /**
     * Start a vision loop on the backend. Returns the response JSON
     * containing the loop_id, or null on failure.
     */
    fun startVisionLoop(goal: String, maxSteps: Int = 25): String? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/vision-loop/start")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.doInput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val requestBody = JSONObject()
            requestBody.put("goal", goal)
            requestBody.put("max_steps", maxSteps)

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(requestBody.toString())
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                Log.i("ApiClient", "Vision loop started successfully")
                return response.toString()
            } else {
                Log.e("ApiClient", "Failed to start vision loop: $responseCode")
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Failed to start vision loop", e)
        } finally {
            connection?.disconnect()
        }
        return null
    }

    /**
     * Send a screenshot to the backend for the active vision loop.
     * The backend analyzes it with Gemini Vision and returns the SINGLE
     * next action to execute.
     *
     * @return Response JSON with "action" field containing the next action, or null
     */
    fun sendScreenshotForVision(screenshotBase64: String, loopId: String): String? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/vision-loop/screenshot")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.doInput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 60000  // Gemini vision can take a while

            val requestBody = JSONObject()
            requestBody.put("loop_id", loopId)
            requestBody.put("screenshot", screenshotBase64)

            Log.i("ApiClient", "Sending screenshot for vision (${screenshotBase64.length / 1024}KB)")

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(requestBody.toString())
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                Log.i("ApiClient", "Vision loop response received")
                return response.toString()
            } else {
                Log.e("ApiClient", "Vision screenshot failed: $responseCode")
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Failed to send vision screenshot", e)
        } finally {
            connection?.disconnect()
        }
        return null
    }

    /**
     * Report that a vision loop action was completed (or failed).
     */
    fun reportVisionActionComplete(
        loopId: String,
        stepIndex: Int,
        success: Boolean,
        error: String? = null
    ): String? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/vision-loop/action-complete")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.doInput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val requestBody = JSONObject()
            requestBody.put("loop_id", loopId)
            requestBody.put("step_index", stepIndex)
            requestBody.put("success", success)
            if (error != null) {
                requestBody.put("error", error)
            }

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(requestBody.toString())
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                return response.toString()
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Failed to report vision action complete", e)
        } finally {
            connection?.disconnect()
        }
        return null
    }

    /**
     * Abort the running vision loop.
     */
    fun abortVisionLoop(loopId: String): Boolean {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/vision-loop/abort")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            // Send empty JSON body
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write("{}")
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            Log.i("ApiClient", "Vision loop abort response: $responseCode")
            return responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.e("ApiClient", "Failed to abort vision loop", e)
        } finally {
            connection?.disconnect()
        }
        return false
    }
}
