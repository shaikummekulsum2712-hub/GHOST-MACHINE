package com.example.ghostmachine

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class GhostAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GhostService"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isRunning = AtomicBoolean(false)

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var ghostButton: Button? = null
    private var statusView: TextView? = null

    private var speechRecognizer: SpeechRecognizer? = null

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
        fun centerX(): Float = (left + right) / 2f
        fun centerY(): Float = (top + bottom) / 2f
    }

    data class ParsedCommand(
        val intent: String,
        val target: String
    )

    data class AndroidDecision(
        val confident: Boolean,
        val action: String,
        val element: UiElement?,
        val text: String?,
        val direction: String?,
        val reason: String,
        val confidence: Double
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
        createOverlay()
        initSpeechRecognizer()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We do not need to react to every event right now.
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }

        overlayView = null
        ghostButton = null
        statusView = null
    }

    private fun createOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 10, 10, 10)
        }

        val button = Button(this).apply {
            text = "👻"
            textSize = 22f
            setOnClickListener {
                startVoiceInput()
            }
        }

        val status = TextView(this).apply {
            text = "Ready"
            textSize = 13f
            setPadding(8, 4, 8, 4)
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }

        container.addView(button)
        container.addView(status)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 420
        }

        overlayView = container
        ghostButton = button
        statusView = status

        try {
            windowManager?.addView(container, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay", e)
        }
    }

    private fun setOverlayStatus(message: String) {
        mainHandler.post {
            statusView?.text = message
        }
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

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setOverlayStatus("Speech not available")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                setOverlayStatus("Listening...")
            }

            override fun onBeginningOfSpeech() {
                setOverlayStatus("Hearing...")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                setOverlayStatus("Processing voice...")
            }

            override fun onError(error: Int) {
                Log.e(TAG, "Speech error: $error")
                setOverlayStatus("Voice error. Try again.")
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val command = matches?.firstOrNull()?.trim()

                if (command.isNullOrBlank()) {
                    setOverlayStatus("No command heard")
                    return
                }

                Log.d(TAG, "Voice command: $command")
                runCommandFromOverlay(command)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()?.trim()
                if (!partial.isNullOrBlank()) {
                    setOverlayStatus("Hearing: $partial")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startVoiceInput() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            setOverlayStatus("Mic permission needed")
            showToast("Open app and allow microphone permission")
            return
        }

        if (isRunning.get()) {
            setOverlayStatus("Busy...")
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            setOverlayStatus("Could not start voice")
        }
    }

    private fun runCommandFromOverlay(command: String) {
        showToast("Ghost heard: $command")
        setOverlayStatus("Heard: $command")

        if (handleDirectOpenCommand(command)) {
            mainHandler.postDelayed({ showButtonAgain() }, 900)
            return
        }

        Thread {
            runVisionLoop(command)
        }.start()
    }

    private fun handleDirectOpenCommand(command: String): Boolean {
        val lower = command.lowercase().trim()

        val packageName = when {
            lower.contains("open whatsapp business") -> "com.whatsapp.w4b"
            lower == "open whatsapp" || lower.contains("open whatsapp") -> "com.whatsapp"
            lower.contains("open youtube") -> "com.google.android.youtube"
            lower.contains("open chrome") -> "com.android.chrome"
            lower.contains("open google") -> "com.google.android.googlequicksearchbox"
            else -> null
        }

        if (packageName != null) {
            return openApp(packageName)
        }

        if (lower.contains("open settings")) {
            return try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                setOverlayStatus("Opened settings")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Open settings failed", e)
                false
            }
        }

        return false
    }

    private fun openApp(packageName: String): Boolean {
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                setOverlayStatus("App not found")
                return false
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            setOverlayStatus("Opened app")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Open app failed: $packageName", e)
            setOverlayStatus("Could not open app")
            false
        }
    }

    private fun parseCommand(command: String): ParsedCommand {
        val lower = command.lowercase().trim()

        return when {
            lower.startsWith("search for ") -> {
                ParsedCommand("search", command.substringAfter("search for").trim())
            }

            lower.startsWith("type ") -> {
                ParsedCommand("type", command.substringAfter("type").trim())
            }

            lower.startsWith("write ") -> {
                ParsedCommand("type", command.substringAfter("write").trim())
            }

            lower.startsWith("open chat ") -> {
                ParsedCommand("open_chat", command.substringAfter("open chat").trim())
            }

            lower.startsWith("tap ") -> {
                ParsedCommand("tap", command.substringAfter("tap").trim())
            }

            lower.startsWith("click ") -> {
                ParsedCommand("tap", command.substringAfter("click").trim())
            }

            lower.contains("scroll down") -> ParsedCommand("scroll", "down")
            lower.contains("scroll up") -> ParsedCommand("scroll", "up")
            lower.contains("swipe down") -> ParsedCommand("scroll", "down")
            lower.contains("swipe up") -> ParsedCommand("scroll", "up")
            lower.contains("go back") -> ParsedCommand("back", "")
            lower == "back" -> ParsedCommand("back", "")
            lower == "home" -> ParsedCommand("home", "")

            else -> ParsedCommand("unknown", command)
        }
    }

    private fun runVisionLoop(command: String) {
        if (!isRunning.compareAndSet(false, true)) {
            showOverlayWithStatus("Already running")
            return
        }

        val parsed = parseCommand(command)
        var previousAction: String? = null
        val maxSteps = 2

        try {
            for (step in 1..maxSteps) {
                showOverlayWithStatus("Checking screen...")

                val elements = collectScreenElements()

                if (isTaskDone(parsed, elements, previousAction)) {
                    showOverlayWithStatus("Done")
                    Thread.sleep(700)
                    break
                }

                val androidDecision = decideWithAndroidOnly(parsed, elements)

                if (androidDecision.confident) {
                    showOverlayWithStatus("Doing it...")

                    val success = executeAndroidDecision(androidDecision)
                    previousAction = androidDecision.action

                    if (!success) {
                        showOverlayWithStatus("I could not do that.")
                        Thread.sleep(900)
                        break
                    }

                    Thread.sleep(800)

                    val newElements = collectScreenElements()
                    if (isTaskDone(parsed, newElements, previousAction)) {
                        showOverlayWithStatus("Done")
                        Thread.sleep(700)
                        break
                    }

                    continue
                }

                showOverlayWithStatus("Thinking...")
                Thread.sleep(300)

                hideOverlayForScreenshot()
                Thread.sleep(300)

                val screenshotBytes = captureScreenJpegBytes()

                showOverlayWithStatus("Thinking...")

                if (screenshotBytes == null) {
                    showOverlayWithStatus("Could not capture screen.")
                    Thread.sleep(900)
                    break
                }

                val compactElementsJson = elementsToJson(elements, parsed)

                val responseJson = ApiClient.analyzeScreen(
                    command = command,
                    screenshotBytes = screenshotBytes,
                    screenElementsJson = compactElementsJson,
                    parsedIntent = parsed.intent,
                    parsedTarget = parsed.target,
                    androidUncertainty = androidDecision.reason,
                    previousAction = previousAction
                )

                if (responseJson == null) {
                    showOverlayWithStatus("Something went wrong.")
                    Thread.sleep(900)
                    break
                }

                showOverlayWithStatus("Executing...")

                val success = executeVlmAction(responseJson, elements)
                previousAction = responseJson

                if (!success) {
                    showOverlayWithStatus("I could not complete this.")
                    Thread.sleep(900)
                    break
                }

                Thread.sleep(900)
            }
        } catch (e: Exception) {
            Log.e(TAG, "runVisionLoop failed", e)
            showOverlayWithStatus("Something went wrong.")
            Thread.sleep(900)
        } finally {
            isRunning.set(false)
            showButtonAgain()
        }
    }

    private fun decideWithAndroidOnly(
        parsed: ParsedCommand,
        elements: List<UiElement>
    ): AndroidDecision {
        val intent = parsed.intent
        val target = parsed.target.lowercase().trim()

        if (intent == "back") {
            return AndroidDecision(true, "back", null, null, null, "direct back", 1.0)
        }

        if (intent == "home") {
            return AndroidDecision(true, "home", null, null, null, "direct home", 1.0)
        }

        if (intent == "scroll") {
            return AndroidDecision(true, "swipe", null, null, parsed.target, "direct scroll", 1.0)
        }

        if (intent == "type") {
            val editable = elements.firstOrNull { it.editable }
            if (editable != null || isAnyInputFocused()) {
                return AndroidDecision(true, "type", editable, parsed.target, null, "input ready", 0.95)
            }
        }

        if (intent == "search") {
            val searchMatches = elements.filter {
                val t = (it.text ?: "").lowercase()
                val d = (it.contentDescription ?: "").lowercase()
                it.editable || t.contains("search") || d.contains("search")
            }

            val editableSearch = searchMatches.firstOrNull { it.editable }

            if (editableSearch != null) {
                return AndroidDecision(
                    true,
                    "tap_then_type",
                    editableSearch,
                    parsed.target,
                    null,
                    "search field found",
                    0.95
                )
            }

            if (searchMatches.size == 1) {
                return AndroidDecision(
                    true,
                    "tap_then_type",
                    searchMatches.first(),
                    parsed.target,
                    null,
                    "search element found",
                    0.85
                )
            }

            return AndroidDecision(
                false,
                "none",
                null,
                null,
                null,
                "multiple or no search matches",
                0.4
            )
        }

        if (intent == "tap") {
            val matches = elements.filter {
                val t = (it.text ?: "").lowercase()
                val d = (it.contentDescription ?: "").lowercase()
                target.isNotBlank() && (t.contains(target) || d.contains(target))
            }

            if (matches.size == 1) {
                return AndroidDecision(
                    true,
                    "tap",
                    matches.first(),
                    null,
                    null,
                    "single tap match",
                    0.9
                )
            }

            return AndroidDecision(
                false,
                "none",
                null,
                null,
                null,
                "tap target unclear",
                0.35
            )
        }

        if (intent == "open_chat") {
            val matches = elements.filter {
                val t = (it.text ?: "").lowercase()
                target.isNotBlank() && t.contains(target)
            }

            if (matches.size == 1) {
                return AndroidDecision(
                    true,
                    "tap",
                    matches.first(),
                    null,
                    null,
                    "chat match found",
                    0.9
                )
            }

            return AndroidDecision(
                false,
                "none",
                null,
                null,
                null,
                "chat target unclear",
                0.35
            )
        }

        return AndroidDecision(
            false,
            "none",
            null,
            null,
            null,
            "unknown command",
            0.2
        )
    }

    private fun executeAndroidDecision(decision: AndroidDecision): Boolean {
        return when (decision.action) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)

            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)

            "swipe" -> {
                performDirectionalSwipe(decision.direction ?: "down")
            }

            "type" -> {
                val text = decision.text ?: return false
                performType(text)
            }

            "tap" -> {
                val element = decision.element ?: return false
                performTap(element.centerX(), element.centerY())
            }

            "tap_then_type" -> {
                val element = decision.element ?: return false
                val text = decision.text ?: return false

                val tapped = performTap(element.centerX(), element.centerY())
                if (!tapped) return false

                Thread.sleep(700)
                performType(text)
            }

            else -> false
        }
    }

    private fun executeVlmAction(responseJson: String, elements: List<UiElement>): Boolean {
        return try {
            val obj = JSONObject(responseJson)

            val action = obj.optString("action")
            val elementId = if (obj.isNull("element_id")) null else obj.optInt("element_id")
            val gridCell = if (obj.isNull("grid_cell")) null else obj.optString("grid_cell")
            val text = if (obj.isNull("text")) null else obj.optString("text")
            val direction = if (obj.isNull("direction")) null else obj.optString("direction")
            val reason = obj.optString("reason", "")

            when (action) {
                "done" -> {
                    showOverlayWithStatus("Done")
                    true
                }

                "ask_user" -> {
                    showOverlayWithStatus(reason.ifBlank { "I need help." })
                    false
                }

                "type" -> {
                    val value = text ?: return false
                    performType(value)
                }

                "swipe" -> {
                    performDirectionalSwipe(direction ?: "down")
                }

                "tap" -> {
                    if (elementId != null) {
                        val element = elements.firstOrNull { it.id == elementId }
                        if (element != null) {
                            return performTap(element.centerX(), element.centerY())
                        }
                    }

                    if (!gridCell.isNullOrBlank()) {
                        val metrics = resources.displayMetrics
                        val point = gridCellToPoint(
                            gridCell,
                            metrics.widthPixels,
                            metrics.heightPixels
                        )
                        if (point != null) {
                            return performTap(point.first, point.second)
                        }
                    }

                    val x = if (obj.isNull("x")) null else obj.optDouble("x").toFloat()
                    val y = if (obj.isNull("y")) null else obj.optDouble("y").toFloat()

                    if (x != null && y != null) {
                        return performTap(x, y)
                    }

                    false
                }

                "wait" -> {
                    Thread.sleep(800)
                    true
                }

                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeVlmAction failed", e)
            false
        }
    }

    private fun isTaskDone(
        parsed: ParsedCommand,
        elements: List<UiElement>,
        lastAction: String?
    ): Boolean {
        val intent = parsed.intent
        val target = parsed.target.lowercase().trim()

        if (lastAction == "tap_then_type" && intent == "search") {
            return true
        }

        if (lastAction == "type" && intent == "type") {
            return true
        }

        if (lastAction == "tap" && intent == "tap") {
            return true
        }

        if (lastAction == "swipe" && intent == "scroll") {
            return true
        }

        if (intent == "open_chat" && target.isNotBlank()) {
            val hasTarget = elements.any {
                val t = (it.text ?: "").lowercase()
                t.contains(target)
            }

            val hasMessageInput = elements.any {
                val t = (it.text ?: "").lowercase()
                val d = (it.contentDescription ?: "").lowercase()
                it.editable || t.contains("message") || d.contains("message")
            }

            if (hasTarget && hasMessageInput) {
                return true
            }
        }

        return false
    }

    private fun collectScreenElements(): List<UiElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<UiElement>()

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (results.size > 80) return

            val rect = Rect()
            node.getBoundsInScreen(rect)

            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val className = node.className?.toString() ?: ""

            val editable = try {
                node.isEditable || className.contains("EditText", ignoreCase = true)
            } catch (_: Exception) {
                className.contains("EditText", ignoreCase = true)
            }

            val usefulText = !text.isNullOrBlank() || !desc.isNullOrBlank()
            val validBounds = rect.width() > 5 && rect.height() > 5

            if (validBounds && (usefulText || node.isClickable || editable)) {
                results.add(
                    UiElement(
                        id = results.size,
                        text = text,
                        contentDescription = desc,
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom,
                        clickable = node.isClickable,
                        editable = editable
                    )
                )
            }

            for (i in 0 until node.childCount) {
                visit(node.getChild(i))
            }
        }

        visit(root)
        return results
    }

    private fun elementsToJson(elements: List<UiElement>, parsed: ParsedCommand): String {
        val targetWords = parsed.target.lowercase()
            .split(" ")
            .filter { it.length > 1 }

        val intent = parsed.intent

        val ranked = elements.sortedByDescending { element ->
            var score = 0

            val t = (element.text ?: "").lowercase()
            val d = (element.contentDescription ?: "").lowercase()

            if (element.editable) score += 100
            if (element.clickable) score += 40

            if (intent == "search" && (t.contains("search") || d.contains("search"))) {
                score += 100
            }

            targetWords.forEach { word ->
                if (t.contains(word) || d.contains(word)) score += 80
            }

            score
        }.take(15)

        val arr = JSONArray()

        ranked.forEach { element ->
            val obj = JSONObject()
            obj.put("i", element.id)
            obj.put("t", element.text ?: "")
            obj.put("d", element.contentDescription ?: "")
            obj.put(
                "b",
                JSONArray(
                    listOf(
                        element.left,
                        element.top,
                        element.right,
                        element.bottom
                    )
                )
            )
            obj.put("c", if (element.clickable) 1 else 0)
            obj.put("e", if (element.editable) 1 else 0)
            arr.put(obj)
        }

        return arr.toString()
    }

    private fun isAnyInputFocused(): Boolean {
        return try {
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        val className = node.className?.toString() ?: ""
        val editable = try {
            node.isEditable || className.contains("EditText", ignoreCase = true)
        } catch (_: Exception) {
            className.contains("EditText", ignoreCase = true)
        }

        if (editable) return node

        for (i in 0 until node.childCount) {
            val found = findEditableNode(node.getChild(i))
            if (found != null) return found
        }

        return null
    }

    private fun performType(text: String): Boolean {
        return try {
            val root = rootInActiveWindow
            val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val target = focused ?: findEditableNode(root)

            if (target == null) {
                Log.e(TAG, "No editable field found")
                return false
            }

            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }

            val success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            if (!success) {
                Log.e(TAG, "ACTION_SET_TEXT failed")
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "performType failed", e)
            false
        }
    }

    private fun performTap(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 1f, y + 1f)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 180))
                .build()

            val latch = CountDownLatch(1)
            var success = false

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
                mainHandler
            )

            latch.await(2, TimeUnit.SECONDS)
            success
        } catch (e: Exception) {
            Log.e(TAG, "performTap failed", e)
            false
        }
    }

    private fun performDirectionalSwipe(direction: String): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        val startX = width / 2f
        val endX = width / 2f
        var startY = height / 2f
        var endY = height / 2f

        when (direction.lowercase()) {
            "up" -> {
                startY = height * 0.75f
                endY = height * 0.30f
            }

            "down" -> {
                startY = height * 0.30f
                endY = height * 0.75f
            }

            "left" -> {
                return performHorizontalSwipe(left = true)
            }

            "right" -> {
                return performHorizontalSwipe(left = false)
            }
        }

        return performSwipe(startX, startY, endX, endY)
    }

    private fun performHorizontalSwipe(left: Boolean): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        val startY = height / 2f
        val endY = height / 2f

        val startX = if (left) width * 0.75f else width * 0.25f
        val endX = if (left) width * 0.25f else width * 0.75f

        return performSwipe(startX, startY, endX, endY)
    }

    private fun performSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ): Boolean {
        return try {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 450))
                .build()

            val latch = CountDownLatch(1)
            var success = false

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
                mainHandler
            )

            latch.await(3, TimeUnit.SECONDS)
            success
        } catch (e: Exception) {
            Log.e(TAG, "performSwipe failed", e)
            false
        }
    }

    private fun gridCellToPoint(
        gridCell: String,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Float, Float>? {
        if (gridCell.length < 2) return null

        val colChar = gridCell[0].uppercaseChar()
        val rowText = gridCell.substring(1)

        val colIndex = colChar - 'A'
        val rowIndex = rowText.toIntOrNull()?.minus(1) ?: return null

        if (colIndex !in 0..9 || rowIndex !in 0..9) return null

        val cellWidth = screenWidth / 10f
        val cellHeight = screenHeight / 10f

        val x = colIndex * cellWidth + cellWidth / 2f
        val y = rowIndex * cellHeight + cellHeight / 2f

        return Pair(x, y)
    }

    private fun captureScreenJpegBytes(): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.e(TAG, "takeScreenshot requires Android 11+")
            return null
        }

        val latch = CountDownLatch(1)
        var resultBytes: ByteArray? = null

        val executor = java.util.concurrent.Executor { runnable ->
            mainHandler.post(runnable)
        }

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                hardwareBuffer,
                                screenshot.colorSpace
                            )?.copy(Bitmap.Config.ARGB_8888, false)

                            hardwareBuffer.close()

                            if (bitmap == null) {
                                Log.e(TAG, "Bitmap from screenshot is null")
                                latch.countDown()
                                return
                            }

                            val resizedBitmap = resizeBitmapForVlm(bitmap, maxWidth = 540)
                            val outputStream = ByteArrayOutputStream()

                            resizedBitmap.compress(
                                Bitmap.CompressFormat.JPEG,
                                45,
                                outputStream
                            )

                            resultBytes = outputStream.toByteArray()

                            if (resizedBitmap != bitmap) {
                                resizedBitmap.recycle()
                            }

                            bitmap.recycle()
                        } catch (e: Exception) {
                            Log.e(TAG, "Screenshot onSuccess failed", e)
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "takeScreenshot failed: $errorCode")
                        latch.countDown()
                    }
                }
            )

            latch.await(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "captureScreenJpegBytes failed", e)
            return null
        }

        return resultBytes
    }

    private fun resizeBitmapForVlm(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) {
            return bitmap
        }

        val scale = maxWidth.toFloat() / max(1, bitmap.width).toFloat()
        val newHeight = max(1, (bitmap.height * scale).toInt())

        return Bitmap.createScaledBitmap(
            bitmap,
            maxWidth,
            newHeight,
            true
        )
    }
}