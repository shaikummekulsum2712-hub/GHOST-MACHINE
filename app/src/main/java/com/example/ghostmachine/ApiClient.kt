package com.example.ghostmachine

import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

object ApiClient {

    // With ADB reverse:
    // adb reverse tcp:8000 tcp:8000
    private const val BASE_URL = "http://127.0.0.1:8000"

    fun analyzeScreen(command: String, screenshotBytes: ByteArray): String? {
        var connection: HttpURLConnection? = null

        return try {
            val boundary = "----GhostMachineBoundary${UUID.randomUUID()}"
            val lineEnd = "\r\n"
            val twoHyphens = "--"

            val url = URL("$BASE_URL/analyze-screen")
            connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.doInput = true
            connection.doOutput = true
            connection.useCaches = false
            connection.connectTimeout = 15000
            connection.readTimeout = 70000

            connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary"
            )

            val outputStream = DataOutputStream(connection.outputStream)

            // Form field: command
            outputStream.writeBytes(twoHyphens + boundary + lineEnd)
            outputStream.writeBytes(
                "Content-Disposition: form-data; name=\"command\"$lineEnd"
            )
            outputStream.writeBytes(lineEnd)
            outputStream.writeBytes(command)
            outputStream.writeBytes(lineEnd)

            // File field: screenshot
            outputStream.writeBytes(twoHyphens + boundary + lineEnd)
            outputStream.writeBytes(
                "Content-Disposition: form-data; name=\"screenshot\"; filename=\"screen.jpg\"$lineEnd"
            )
            outputStream.writeBytes("Content-Type: image/jpeg$lineEnd")
            outputStream.writeBytes(lineEnd)
            outputStream.write(screenshotBytes)
            outputStream.writeBytes(lineEnd)

            // End multipart body
            outputStream.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd)
            outputStream.flush()
            outputStream.close()

            val responseCode = connection.responseCode
            val inputStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val reader = BufferedReader(InputStreamReader(inputStream))
            val response = StringBuilder()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                response.append(line)
            }

            reader.close()

            Log.i("ApiClient", "Backend response code: $responseCode")
            Log.i("ApiClient", "Backend response: $response")

            if (responseCode in 200..299) {
                response.toString()
            } else {
                null
            }

        } catch (e: Exception) {
            Log.e("ApiClient", "Failed to call /analyze-screen", e)
            null
        } finally {
            connection?.disconnect()
        }
    }
}