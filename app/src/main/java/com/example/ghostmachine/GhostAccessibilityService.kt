package com.example.ghostmachine

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GhostAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var ghostButton: Button? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        var instance: GhostAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        showFloatingButton()
        Log.i("GhostService", "Accessibility service connected")
    }

    override fun onDestroy() {
        destroySpeechRecognizer()
        removeFloatingButton()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.i("GhostService", "Accessibility interrupted")
    }

    private fun showFloatingButton() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        ghostButton = Button(this).apply {
            text = "👻"
            textSize = 22f
            setOnClickListener {
                startListeningFromOverlay()
            }
        }

        val params = WindowManager.LayoutParams(
            150,
            150,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.END
        params.x = 20
        params.y = 250

        try {
            windowManager?.addView(ghostButton, params)
            Log.i("GhostService", "Floating button added")
        } catch (e: Exception) {
            Log.e("GhostService", "Failed to add floating button", e)
        }
    }

    private fun removeFloatingButton() {
        try {
            ghostButton?.let {
                windowManager?.removeView(it)
                ghostButton = null
            }
        } catch (e: Exception) {
            Log.e("GhostService", "Failed to remove floating button", e)
        }
    }

    private fun startListeningFromOverlay() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showToast("Open Ghost Machine app and grant mic permission first")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showToast("Speech recognition not available on this phone")
            return
        }

        showToast("Listening...")

        ghostButton?.text = "🎙️"

        destroySpeechRecognizer()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.i("GhostService", "Ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Log.i("GhostService", "Speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.i("GhostService", "Speech ended")
            }

            override fun onError(error: Int) {
                Log.e("GhostService", "Speech error: $error")
                ghostButton?.text = "👻"
                showToast("Voice failed. Try again.")
                destroySpeechRecognizer()
            }

            override fun onResults(results: Bundle?) {
                val spokenText = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()

                ghostButton?.text = "👻"
                destroySpeechRecognizer()

                if (spokenText.isNullOrBlank()) {
                    showToast("No command heard")
                    return
                }

                Log.i("GhostService", "Voice command: $spokenText")
                runCommandFromOverlay(spokenText)
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer?.startListening(intent)
    }

    private fun destroySpeechRecognizer() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
    }

    private fun runCommandFromOverlay(command: String) {
        showToast("Ghost heard: $command")

        if (handleDirectOpenCommand(command)) {
            return
        }

        ghostButton?.visibility = android.view.View.GONE

        mainHandler.postDelayed({
            Thread {
                val screenshotBytes = captureScreenJpegBytes()

                if (screenshotBytes == null) {
                    showButtonAgain()
                    showToast("Screenshot failed. Need Android 11+.")
                    return@Thread
                }

                val responseJson = ApiClient.analyzeScreen(command, screenshotBytes)

                if (responseJson == null) {
                    showButtonAgain()
                    showToast("Backend failed. Check server/ADB reverse.")
                    return@Thread
                }

                Log.i("GhostService", "Backend action: $responseJson")

                val success = executeAction(responseJson)

                showButtonAgain()

                if (success) {
                    showToast("Ghost action executed")
                } else {
                    showToast("Ghost could not execute action")
                }
            }.start()
        }, 1200)
    }

    private fun handleDirectOpenCommand(command: String): Boolean {
        val lower = command.lowercase()

        if (lower.contains("open whatsapp") || lower.contains("launch whatsapp")) {
            return openAppByPackage("com.whatsapp", "WhatsApp")
        }

        if (lower.contains("open settings") || lower.contains("launch settings")) {
            val intent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            showToast("Opened Settings")
            return true
        }

        return false
    }

    private fun openAppByPackage(packageName: String, appName: String): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

        if (launchIntent == null) {
            showToast("$appName not installed")
            return true
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        showToast("Opened $appName")
        return true
    }

    private fun showButtonAgain() {
        mainHandler.post {
            ghostButton?.visibility = android.view.View.VISIBLE
        }
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun captureScreenJpegBytes(): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.e("GhostService", "Screenshot needs Android 11+")
            return null
        }

        val latch = CountDownLatch(1)
        var resultBitmap: Bitmap? = null

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )

                            resultBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)

                            hardwareBitmap?.recycle()
                            screenshot.hardwareBuffer.close()
                        } catch (e: Exception) {
                            Log.e("GhostService", "Screenshot processing failed", e)
                        }

                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e("GhostService", "Screenshot failed code: $errorCode")
                        latch.countDown()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("GhostService", "takeScreenshot error", e)
            return null
        }

        val completed = latch.await(5, TimeUnit.SECONDS)

        if (!completed) {
            Log.e("GhostService", "Screenshot timeout")
            return null
        }

        val bitmap = resultBitmap ?: return null

        return try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            bitmap.recycle()
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e("GhostService", "JPEG conversion failed", e)
            bitmap.recycle()
            null
        }
    }

    private fun executeAction(actionJson: String): Boolean {
        return try {
            val obj = JSONObject(actionJson)
            val action = obj.optString("action")

            when (action) {
                "tap" -> {
                    val x = obj.optDouble("x", -1.0).toFloat()
                    val y = obj.optDouble("y", -1.0).toFloat()

                    if (x < 0 || y < 0) return false

                    performTap(x, y)
                }

                "swipe" -> {
                    val direction = obj.optString("direction", "")
                    performDirectionalSwipe(direction)
                }

                "type" -> {
                    val text = obj.optString("text", "")
                    performType(text)
                }

                "wait" -> {
                    Thread.sleep(1000)
                    true
                }

                "done" -> true

                "ask_user" -> {
                    val reason = obj.optString("reason", "Need user help")
                    showToast(reason)
                    false
                }

                else -> false
            }
        } catch (e: Exception) {
            Log.e("GhostService", "Action execution failed", e)
            false
        }
    }

    private fun performTap(x: Float, y: Float): Boolean {
        val latch = CountDownLatch(1)
        var success = false

        mainHandler.post {
            val path = Path().apply {
                moveTo(x, y)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
                .build()

            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        success = true
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        success = false
                        latch.countDown()
                    }
                },
                null
            )
        }

        latch.await(3, TimeUnit.SECONDS)
        Log.i("GhostService", "Tap result: $success at $x,$y")
        return success
    }

    private fun performDirectionalSwipe(direction: String): Boolean {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()

        val centerX = width / 2f
        val centerY = height / 2f

        val startX: Float
        val startY: Float
        val endX: Float
        val endY: Float

        when (direction) {
            "up" -> {
                startX = centerX
                startY = height * 0.75f
                endX = centerX
                endY = height * 0.30f
            }

            "down" -> {
                startX = centerX
                startY = height * 0.30f
                endX = centerX
                endY = height * 0.75f
            }

            "left" -> {
                startX = width * 0.80f
                startY = centerY
                endX = width * 0.20f
                endY = centerY
            }

            "right" -> {
                startX = width * 0.20f
                startY = centerY
                endX = width * 0.80f
                endY = centerY
            }

            else -> return false
        }

        return performSwipe(startX, startY, endX, endY, 500)
    }

    private fun performSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long
    ): Boolean {
        val latch = CountDownLatch(1)
        var success = false

        mainHandler.post {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()

            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        success = true
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        success = false
                        latch.countDown()
                    }
                },
                null
            )
        }

        latch.await(4, TimeUnit.SECONDS)
        Log.i("GhostService", "Swipe result: $success")
        return success
    }

    private fun performType(text: String): Boolean {
        val latch = CountDownLatch(1)
        var success = false

        mainHandler.post {
            try {
                var targetNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

                if (targetNode == null) {
                    targetNode = findEditableNode(rootInActiveWindow)
                }

                if (targetNode == null) {
                    success = false
                    latch.countDown()
                    return@post
                }

                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )

                success = targetNode.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    args
                )

                targetNode.recycle()
                latch.countDown()
            } catch (e: Exception) {
                Log.e("GhostService", "Typing failed", e)
                success = false
                latch.countDown()
            }
        }

        latch.await(3, TimeUnit.SECONDS)
        return success
    }

    private fun findEditableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null

        if (root.isEditable) {
            return root
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findEditableNode(child)

            if (result != null) {
                return result
            }

            child.recycle()
        }

        return null
    }
}