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

    fun getNextAction(command: String): String? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/next-action")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.doInput = true

            // Write JSON request body
            val requestBody = JSONObject()
            requestBody.put("command", command)
            requestBody.put("sender", "android")

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
}
