package com.example.ghostmachine

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
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
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class GhostAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var ghostButton: Button? = null
    private var statusView: TextView? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    data class UiElement(
        val id: Int,
        val text: String?,
        val contentDescription: String?,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val clickable: Boolean,
        val editable: Boolean
    ) {
        fun centerX(): Float = ((left + right) / 2f)
        fun centerY(): Float = ((top + bottom) / 2f)
    }

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

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }

        ghostButton = Button(this).apply {
            text = "👻"
            textSize = 22f
            setOnClickListener {
                startListeningFromOverlay()
            }
        }

        statusView = TextView(this).apply {
            text = "Ready"
            textSize = 12f
            setPadding(8, 4, 8, 4)
            maxLines = 2
        }

        container.addView(ghostButton, LinearLayout.LayoutParams(150, 150))
        container.addView(
            statusView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        overlayView = container

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.END
        params.x = 20
        params.y = 420

        try {
            windowManager?.addView(overlayView, params)
            Log.i("GhostService", "Floating overlay added")
        } catch (e: Exception) {
            Log.e("GhostService", "Failed to add floating overlay", e)
        }
    }

    private fun removeFloatingButton() {
        try {
            overlayView?.let {
                windowManager?.removeView(it)
                overlayView = null
                ghostButton = null
                statusView = null
            }
        } catch (e: Exception) {
            Log.e("GhostService", "Failed to remove floating overlay", e)
        }
    }

    private fun setOverlayStatus(message: String) {
        mainHandler.post {
            statusView?.text = message
        }
    }

    private fun startListeningFromOverlay() {
        Log.i("GhostService", "Ghost button clicked")

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
        setOverlayStatus("Listening...")
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
                Log.e("GhostService", "Speech error code: $error")
                ghostButton?.text = "👻"
                setOverlayStatus("Ready")
                destroySpeechRecognizer()
                showToast("Voice failed. Try again.")
            }

            override fun onResults(results: Bundle?) {
                val spokenText = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()

                ghostButton?.text = "👻"
                destroySpeechRecognizer()

                if (spokenText.isNullOrBlank()) {
                    setOverlayStatus("Ready")
                    showToast("No command heard")
                    return
                }

                Log.i("GhostService", "Voice command: $spokenText")
                setOverlayStatus("Heard: $spokenText")
                runCommandFromOverlay(spokenText)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()

                Log.i("GhostService", "Partial speech: $partial")

                if (!partial.isNullOrBlank()) {
                    setOverlayStatus("Hearing: $partial")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
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
        setOverlayStatus("Heard: $command")

        if (handleDirectOpenCommand(command)) {
            setOverlayStatus("Ready")
            return
        }

        mainHandler.postDelayed({
            Thread {
                runVisionLoop(command)
            }.start()
        }, 700)
    }

    private fun runVisionLoop(command: String) {
        val maxSteps = 4
        var lastActionSignature = ""

        for (step in 1..maxSteps) {
            showOverlayWithStatus("Capturing screen...")
            Thread.sleep(400)

            hideOverlayForScreenshot()
            Thread.sleep(500)

            val screenshotBytes = captureScreenJpegBytes()

            if (screenshotBytes == null) {
                showToast("Screenshot failed")
                break
            }
            showOverlayWithStatus("Thinking...")

            val elements = collectScreenElements()
            val elementsJson = elementsToJson(elements)

            Log.i("GhostService", "Collected elements: ${elements.size}")

            val responseJson = ApiClient.analyzeScreen(
                command = command,
                screenshotBytes = screenshotBytes,
                screenElementsJson = elementsJson
            )

            showOverlayWithStatus("Executing...")

            if (responseJson == null) {
                showOverlayWithStatus("Something went wrong. Please try again.")
                showToast("Something went wrong. Check backend logs.")
                break
            }



            Log.i("GhostService", "Backend action: $responseJson")

            val obj = JSONObject(responseJson)
            val action = obj.optString("action", "")
            val elementId = if (obj.isNull("element_id")) null else obj.optInt("element_id")
            val targetText = obj.optString("target_text", "")
            val reason = obj.optString("reason", "")

            val signature = "$action-$elementId-$targetText-$reason"

            if (signature == lastActionSignature) {
                Log.w("GhostService", "Repeated same action. Stopping loop.")
                showToast("Stopped repeated action")
                break
            }

            lastActionSignature = signature

            if (action == "done") {
                showToast("Ghost finished")
                break
            }

            if (action == "ask_user") {
                val friendlyReason = reason.ifBlank { "I could not complete this. Please try again." }
                showOverlayWithStatus(friendlyReason)
                showToast(friendlyReason)
                break
            }

            val success = executeAction(responseJson, elements)

            if (!success) {
                showToast("Action failed")
                break
            }

            Thread.sleep(1300)
        }

        showButtonAgain()
    }

    private fun handleDirectOpenCommand(command: String): Boolean {
        val lower = command.lowercase()

        if (lower.contains("open whatsapp") || lower.contains("launch whatsapp")) {
            val openedNormal = openAppByPackage("com.whatsapp", "WhatsApp")

            if (!openedNormal) {
                openAppByPackage("com.whatsapp.w4b", "WhatsApp Business")
            }

            return true
        }

        if (lower.contains("open google") || lower.contains("launch google")) {
            return openAppByPackage(
                "com.google.android.googlequicksearchbox",
                "Google"
            )
        }

        if (lower.contains("open chrome") || lower.contains("launch chrome")) {
            return openAppByPackage(
                "com.android.chrome",
                "Chrome"
            )
        }

        if (lower.contains("open youtube") || lower.contains("launch youtube")) {
            return openAppByPackage(
                "com.google.android.youtube",
                "YouTube"
            )
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
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

            if (launchIntent == null) {
                Log.e("GhostService", "$appName package not found: $packageName")
                return false
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            showToast("Opened $appName")
            true
        } catch (e: Exception) {
            Log.e("GhostService", "Failed to open $appName", e)
            false
        }
    }

    private fun showButtonAgain() {
        mainHandler.post {
            overlayView?.visibility = View.VISIBLE
            ghostButton?.text = "👻"
            statusView?.text = "Ready"
        }
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun collectScreenElements(): List<UiElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<UiElement>()
        var nextId = 1

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return

            val rect = Rect()
            node.getBoundsInScreen(rect)

            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()

            val useful =
                !text.isNullOrBlank() ||
                        !desc.isNullOrBlank() ||
                        node.isClickable ||
                        node.isEditable

            if (useful && !rect.isEmpty) {
                results.add(
                    UiElement(
                        id = nextId,
                        text = text,
                        contentDescription = desc,
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom,
                        clickable = node.isClickable,
                        editable = node.isEditable
                    )
                )

                nextId++
            }

            for (i in 0 until node.childCount) {
                visit(node.getChild(i))
            }
        }

        visit(root)

        return results.take(35)
    }

    private fun elementsToJson(elements: List<UiElement>): String {
        val arr = JSONArray()

        for (element in elements) {
            val obj = JSONObject()
            obj.put("id", element.id)
            obj.put("text", element.text)
            obj.put("content_description", element.contentDescription)
            obj.put(
                "bounds",
                JSONArray(
                    listOf(
                        element.left,
                        element.top,
                        element.right,
                        element.bottom
                    )
                )
            )
            obj.put("clickable", element.clickable)
            obj.put("editable", element.editable)

            arr.put(obj)
        }

        return arr.toString()
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

    private fun executeAction(
        actionJson: String,
        elements: List<UiElement>
    ): Boolean {
        return try {
            val obj = JSONObject(actionJson)
            val action = obj.optString("action")

            when (action) {
                "tap" -> {
                    val elementId = if (obj.isNull("element_id")) null else obj.optInt("element_id")
                    val x = obj.optDouble("x", -1.0).toFloat()
                    val y = obj.optDouble("y", -1.0).toFloat()
                    val textToType = obj.optString("text", "").trim()

                    var tapSuccess = false

                    if (elementId != null) {
                        val element = elements.firstOrNull { it.id == elementId }

                        if (element != null) {
                            Log.i("GhostService", "Tapping element_id=$elementId")
                            tapSuccess = performTap(element.centerX(), element.centerY())
                        }
                    }

                    if (!tapSuccess && x >= 0 && y >= 0) {
                        Log.i("GhostService", "Using VLM fallback x/y")
                        tapSuccess = performTap(x, y)
                    }

                    if (!tapSuccess) {
                        return false
                    }

                    // Important fix:
                    // If model returned tap + text, treat it as tap field then type text.
                    if (textToType.isNotBlank()) {
                        Log.i("GhostService", "Tap action also has text, typing after tap: $textToType")
                        Thread.sleep(700)
                        return performType(textToType)
                    }

                    true
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
                lineTo(x + 1f, y + 1f)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 180))
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
        }

        return null
    }

    private fun showOverlayWithStatus(message: String) {
        mainHandler.post {
            overlayView?.visibility = View.VISIBLE
            ghostButton?.text = "👻"
            statusView?.text = message
        }
    }

    private fun hideOverlayForScreenshot() {
        mainHandler.post {
            overlayView?.visibility = View.GONE
        }
    }
}