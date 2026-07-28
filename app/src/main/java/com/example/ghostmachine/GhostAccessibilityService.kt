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
        private const val MAX_STEPS = 6
        private const val CONFIDENCE_FLOOR = 0.55
        private const val STEP_DELAY_MS = 700L
        private const val APP_LAUNCH_DELAY_MS = 1200L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isRunning = AtomicBoolean(false)

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var ghostButton: Button? = null
    private var statusView: TextView? = null
    private var ghostTts: GhostTts? = null
    private var currentReplyLanguage: String = "english"

    private var speechRecognizer: SpeechRecognizer? = null

    // -----------------------------------------------------------------
    // Data model
    // -----------------------------------------------------------------

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

    data class ParsedCommand(val intent: String, val target: String)

    enum class ActionType {
        TAP, TAP_THEN_TYPE, TYPE, SWIPE, BACK, HOME, WAIT, DONE, ASK_USER, NONE
    }

    /**
     * Unified representation of "the next thing to do" - whether decided
     * on-device (fast, free) or returned by the backend VLM (slow, costs an
     * API call). Every action, regardless of source, is run through the
     * single executeAction() below, so there is exactly one execution path
     * to trust instead of two that can silently drift apart.
     */
    data class Action(
        val type: ActionType,
        val element: UiElement? = null,
        val text: String? = null,
        val direction: String? = null,
        val gridCell: String? = null,
        val x: Float? = null,
        val y: Float? = null,
        val reason: String = "",
        val userMessage: String? = null,
        val confidence: Double = 1.0,
        val source: String = "android"
    )

    // Every package here MUST also be declared under <queries> in
    // AndroidManifest.xml, or getLaunchIntentForPackage() silently returns
    // null on Android 11+ due to package visibility restrictions.
    private val knownApps = mapOf(
        "whatsapp business" to "com.whatsapp.w4b",
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "google" to "com.google.android.googlequicksearchbox"
    )

    private val sensitiveKeywords = setOf(
        "password", "otp", "one-time", "cvv", "pin", "card number",
        "delete account", "confirm payment", "send money", "transfer",
        "seed phrase", "recovery phrase", "private key", "wire transfer"
    )

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
        createOverlay()
        initSpeechRecognizer()

        ghostTts = GhostTts(this)
        ghostTts?.init()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We do not need to react to every event right now.
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()

        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        try { ghostTts?.shutdown() } catch (_: Exception) {}
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}

        overlayView = null
        ghostButton = null
        statusView = null
        ghostTts = null
    }

    // -----------------------------------------------------------------
    // Overlay UI
    // -----------------------------------------------------------------

    private fun createOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 10, 10, 10)
        }

        val button = Button(this).apply {
            text = "👻"
            textSize = 22f
            setOnClickListener { startVoiceInput() }
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
        mainHandler.post { statusView?.text = message }
    }

    private fun showOverlayWithStatus(message: String) {
        mainHandler.post {
            overlayView?.visibility = View.VISIBLE
            ghostButton?.text = "👻"
            statusView?.text = message
        }
    }

    private fun hideOverlayForScreenshot() {
        mainHandler.post { overlayView?.visibility = View.GONE }
    }

    private fun showButtonAgain() {
        mainHandler.post {
            overlayView?.visibility = View.VISIBLE
            ghostButton?.text = "👻"
            statusView?.text = "Ready"
        }
    }

    private fun showToast(message: String) {
        mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun speakAndFinish(key: String, replyLanguage: String, overrideStatus: String? = null) {
        mainHandler.post {
            val msg = VoiceLanguageManager.message(key, replyLanguage)
            setOverlayStatus(overrideStatus ?: msg)
            ghostTts?.speak(msg, replyLanguage)
            showButtonAgain()
        }
    }

    // -----------------------------------------------------------------
    // Speech input
    // -----------------------------------------------------------------

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setOverlayStatus("Speech not available")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                setOverlayStatus(VoiceLanguageManager.message("listening", currentReplyLanguage))
            }

            override fun onBeginningOfSpeech() { setOverlayStatus("Hearing...") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                setOverlayStatus(VoiceLanguageManager.message("processing_voice", currentReplyLanguage))
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
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

    // -----------------------------------------------------------------
    // Command entry point
    // -----------------------------------------------------------------

    private fun runCommandFromOverlay(command: String) {
        if (!isRunning.compareAndSet(false, true)) {
            setOverlayStatus("Busy, please wait...")
            return
        }

        val voiceContext = VoiceLanguageManager.buildContext(command)
        currentReplyLanguage = voiceContext.replyLanguage

        val understoodMessage = VoiceLanguageManager.message(
            key = "understood",
            language = currentReplyLanguage,
            extra = voiceContext.originalCommand
        )

        showToast(understoodMessage)
        setOverlayStatus(understoodMessage)
        ghostTts?.speak(understoodMessage, currentReplyLanguage)

        val parsed = ParsedCommand(voiceContext.parsedIntent, voiceContext.parsedTarget)

        if (handleDirectOpenCommand(voiceContext.normalizedCommand, parsed)) {
            mainHandler.postDelayed({
                showButtonAgain()
                isRunning.set(false)
            }, 900)
            return
        }

        Thread {
            try {
                Log.d(TAG, "Vision loop started")
                runVisionLoop(voiceContext.normalizedCommand, parsed, currentReplyLanguage)
                Log.d(TAG, "Vision loop finished")
            } catch (e: Exception) {
                Log.e(TAG, "Vision loop crashed", e)
                speakAndFinish("error", currentReplyLanguage)
            } finally {
                isRunning.set(false)
            }
        }.start()
    }

    // -----------------------------------------------------------------
    // Direct app-open shortcuts (single-shot, no follow-up UI interaction)
    // -----------------------------------------------------------------

    private fun handleDirectOpenCommand(command: String, parsed: ParsedCommand): Boolean {
        // open_chat needs a precondition + a follow-up tap, so it always goes
        // through the vision loop instead of being short-circuited here.
        if (parsed.intent == "open_chat") return false


        val lower = command.lowercase().trim()
        if (!lower.contains("open")) return false

        val matchedApp = knownApps.entries
            .sortedByDescending { it.key.length } // "whatsapp business" before "whatsapp"
            .firstOrNull { (name, _) -> lower.contains(name) }

        if (matchedApp != null) {
            return openApp(matchedApp.value)
        }

        if (lower.contains("settings")) {
            return openSettings()
        }

        // Unrecognized app name - fall through to the vision loop rather than
        // silently doing nothing.
        return false
    }

    private fun openSettings(): Boolean {
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

    private fun currentForegroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    private fun ensureAppOpen(packageName: String): Boolean {
        if (currentForegroundPackage() == packageName) return true
        return openApp(packageName)
    }

    private fun resolveChatAppPackage(): String {
        // Defaults to WhatsApp. To support other chat apps, add a keyword
        // check against the original command here, and make sure the
        // package is also declared under <queries> in the manifest.
        return knownApps["whatsapp"] ?: "com.whatsapp"
    }

    private fun performImeEnter(): Boolean {
        return try {
            val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            focused?.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "performImeEnter failed", e)
            false
        }
    }
    // -----------------------------------------------------------------
    // Vision loop
    // -----------------------------------------------------------------


    private fun runVisionLoop(command: String, parsed: ParsedCommand, replyLanguage: String) {
            Log.d(TAG, "==============================")
            Log.d(TAG, "NEW COMMAND: $command | intent=${parsed.intent} target=${parsed.target}")
            Log.d(TAG, "==============================")

            // Guard: don't waste a full VLM cycle on an empty/blank target for
            // intents that require one.
            if (parsed.intent in setOf("search", "type", "type_and_send") && parsed.target.isBlank()) {
                speakAndFinish("need_help", replyLanguage, overrideStatus = "What would you like me to say or search for?")
                return
            }

            // Preconditions: some intents need a specific app in the foreground
            // before anything else makes sense.
            if (parsed.intent == "open_chat") {
                val targetPackage = resolveChatAppPackage()
                if (currentForegroundPackage() != targetPackage) {
                    mainHandler.post { setOverlayStatus(VoiceLanguageManager.message("checking", replyLanguage)) }
                    val launched = ensureAppOpen(targetPackage)
                    Log.d(TAG, "ensureAppOpen($targetPackage) = $launched")
                    if (!launched) {
                        speakAndFinish("error", replyLanguage)
                        return
                    }
                    Thread.sleep(APP_LAUNCH_DELAY_MS)
                } else {
                    // Already in the app - trust the current screen and search it
                    // directly. (Heuristic back-press removed: it was misfiring on
                    // the chat list itself, since WhatsApp's "more options" button
                    // matched the same signal used to detect a nested screen, so it
                    // was exiting the app instead of staying put.)
                }
            }

            if (parsed.intent == "search") {
                val inBrowser = currentForegroundPackage() in setOf("com.android.chrome", "com.google.android.googlequicksearchbox")
                if (!inBrowser) {
                    mainHandler.post { setOverlayStatus(VoiceLanguageManager.message("checking", replyLanguage)) }
                    val launched = ensureAppOpen("com.google.android.googlequicksearchbox")
                    if (!launched) {
                        speakAndFinish("error", replyLanguage)
                        return
                    }
                    Thread.sleep(APP_LAUNCH_DELAY_MS)
                }
            }

            var previousAction: Action? = null
            var previousSignature: String? = null
            var repeatedFailCount = 0
            var lastAttemptedTarget: String? = null

            for (step in 1..MAX_STEPS) {
                Log.d(TAG, "===== STEP $step =====")
                mainHandler.post { setOverlayStatus(VoiceLanguageManager.message("checking", replyLanguage)) }

                val elements = collectScreenElements()
                val signature = computeSignature(elements)
                val screenChanged = previousSignature != null && signature != previousSignature

                Log.d(TAG, "Collected ${elements.size} elements, changed=$screenChanged")

                if (isGoalAchieved(parsed, elements, previousAction, screenChanged)) {
                    Log.d(TAG, "Goal achieved")
                    speakAndFinish("done", replyLanguage)
                    return
                }

                val currentTargetKey = "${parsed.intent}:${parsed.target}"
                if (previousAction != null && !screenChanged && lastAttemptedTarget == currentTargetKey) {
                    repeatedFailCount++
                } else {
                    repeatedFailCount = 0
                }
                lastAttemptedTarget = currentTargetKey

                if (repeatedFailCount >= 2) {
                    Log.d(TAG, "Same action attempted repeatedly with no screen change - escalating to ask_user")
                    speakAndFinish("need_help", replyLanguage, overrideStatus = "I tried a couple of times but nothing changed - can you help me out?")
                    return
                }

                // If the last action was supposed to change the screen but
                // visibly didn't, on-device heuristics already failed once this
                // round - skip straight to the VLM instead of repeating the
                // same guess.
                val lastActionHadNoEffect = previousAction != null &&
                        previousAction.type in setOf(
                    ActionType.TAP, ActionType.TAP_THEN_TYPE, ActionType.SWIPE, ActionType.TYPE
                ) && !screenChanged

                val decision: Action? =
                    if (!lastActionHadNoEffect) decideWithAndroidOnly(parsed, elements) else null

                Log.d(TAG, "Android decision: ${decision?.type} conf=${decision?.confidence} reason=${decision?.reason ?: "skipped (last action had no effect)"}")

                if (decision != null && decision.confidence >= CONFIDENCE_FLOOR) {
                    val executed = executeAction(decision, elements, pressEnter = parsed.intent == "search")
                    Log.d(TAG, "Android executed = $executed")

                    if (executed) {
                        // type_and_send is done the moment the send tap fires -
                        // don't let the loop re-check and re-send.
                        if (parsed.intent == "type_and_send" && decision.type == ActionType.TAP) {
                            Log.d(TAG, "type_and_send: message sent, finishing")
                            speakAndFinish("done", replyLanguage)
                            return
                        }

                        previousAction = decision
                        previousSignature = signature
                        Thread.sleep(STEP_DELAY_MS)
                        continue
                    }
                    Log.d(TAG, "Android execution failed, falling back to VLM")
                }

                // ---- VLM fallback ----
                mainHandler.post { setOverlayStatus(VoiceLanguageManager.message("thinking", replyLanguage)) }

                hideOverlayForScreenshot()
                val screenshotBytes = captureScreenJpegBytes()
                mainHandler.post { overlayView?.visibility = View.VISIBLE }

                if (screenshotBytes == null) {
                    Log.e(TAG, "Screenshot capture failed")
                    break
                }

                val result = ApiClient.analyzeScreen(
                    command = command,
                    screenshotBytes = screenshotBytes,
                    screenElementsJson = elementsToJson(elements, parsed),
                    parsedIntent = parsed.intent,
                    parsedTarget = parsed.target,
                    androidUncertainty = decision?.reason ?: "no confident android decision",
                    previousAction = previousAction?.type?.name?.lowercase(),
                    replyLanguage = replyLanguage
                )

                when (result) {
                    is ApiClient.AnalyzeResult.NetworkError -> {
                        Log.e(TAG, "Network error: ${result.message}")
                        speakAndFinish("error", replyLanguage, overrideStatus = "Backend unreachable")
                        return
                    }

                    is ApiClient.AnalyzeResult.ServerError -> {
                        Log.e(TAG, "Server error ${result.code}: ${result.body}")
                        break
                    }

                    is ApiClient.AnalyzeResult.Success -> {
                        val vlmAction = parseVlmAction(result.json, elements)

                        if (vlmAction == null) {
                            Log.e(TAG, "Could not parse VLM response")
                            break
                        }

                        if (vlmAction.type == ActionType.ASK_USER) {
                            val msg = vlmAction.userMessage
                                ?: VoiceLanguageManager.message("need_help", replyLanguage, vlmAction.reason)
                            showOverlayWithStatus(msg)
                            ghostTts?.speak(msg, replyLanguage)
                            showButtonAgain()
                            return
                        }

                        if (vlmAction.type == ActionType.DONE) {
                            speakAndFinish("done", replyLanguage)
                            return
                        }

                        val executed = executeAction(vlmAction, elements, pressEnter = parsed.intent == "search")
                        Log.d(TAG, "VLM executed = $executed")

                        if (!executed) {
                            Log.e(TAG, "VLM action failed to execute")
                            break
                        }

                        if (parsed.intent == "type_and_send" && vlmAction.type == ActionType.TAP) {
                            Log.d(TAG, "type_and_send: message sent (vlm), finishing")
                            speakAndFinish("done", replyLanguage)
                            return
                        }

                        previousAction = vlmAction
                        previousSignature = signature
                        Thread.sleep(STEP_DELAY_MS)
                    }
                }
            }

            Log.e(TAG, "Vision loop exited after reaching max steps or failure")
            speakAndFinish("error", replyLanguage)
    }
        // -----------------------------------------------------------------
    // Android-only decision engine
    // -----------------------------------------------------------------

    // Invariant: every branch below that returns ActionType.NONE must keep
    // confidence < CONFIDENCE_FLOOR, so the main loop automatically treats
    // it as "not confident" and falls back to the VLM without extra checks.
    private fun decideWithAndroidOnly(parsed: ParsedCommand, elements: List<UiElement>): Action {
        val intent = parsed.intent
        val target = parsed.target.lowercase().trim()

        return when (intent) {
            "back" -> Action(ActionType.BACK, reason = "direct back", confidence = 1.0)
            "home" -> Action(ActionType.HOME, reason = "direct home", confidence = 1.0)
            "scroll" -> Action(ActionType.SWIPE, direction = parsed.target, reason = "direct scroll", confidence = 1.0)

            "type" -> {
                val editable = elements.firstOrNull { it.editable }
                if (editable != null || isAnyInputFocused()) {
                    Action(ActionType.TYPE, element = editable, text = parsed.target, reason = "input ready", confidence = 0.95)
                } else {
                    Action(ActionType.NONE, reason = "no editable field found for type", confidence = 0.3)
                }
            }
            "search" -> {
                val inBrowser = currentForegroundPackage() in setOf("com.android.chrome", "com.google.android.googlequicksearchbox")
                if (!inBrowser) {
                    Action(ActionType.NONE, reason = "search requires a browser open first", confidence = 0.2)
                } else {
                    // existing search-matching logic unchanged
                    val searchMatches = elements.filter {
                        val t = (it.text ?: "").lowercase()
                        val d = (it.contentDescription ?: "").lowercase()
                        it.editable || t.contains("search") || d.contains("search")
                    }
                    val editableSearch = searchMatches.firstOrNull { it.editable }

                    when {
                        editableSearch != null -> Action(
                            ActionType.TAP_THEN_TYPE, element = editableSearch, text = parsed.target,
                            reason = "search field found", confidence = 0.95
                        )
                        searchMatches.size == 1 -> Action(
                            ActionType.TAP_THEN_TYPE, element = searchMatches.first(), text = parsed.target,
                            reason = "search element found", confidence = 0.85
                        )
                        else -> Action(ActionType.NONE, reason = "multiple or no search matches", confidence = 0.4)
                    }
                }
                }


            "send" -> {
                val sendButton = elements.firstOrNull {
                    val d = (it.contentDescription ?: "").lowercase()
                    it.clickable && d == "send"   // exact match, not "contains" - avoids matching "send money"/other noise
                } ?: elements.firstOrNull {
                    val d = (it.contentDescription ?: "").lowercase()
                    it.clickable && d.contains("send") && !d.contains("voice") && !d.contains("money")
                }

                if (sendButton != null) {
                    Action(ActionType.TAP, element = sendButton, reason = "send button found", confidence = 0.9)
                } else {
                    Action(ActionType.NONE, reason = "send button not found", confidence = 0.3)
                }
            }


            "type_and_send" -> {
                val editable = elements.firstOrNull { it.editable }
                val alreadyTyped = editable != null && (editable.text ?: "").lowercase().contains(target)

                if (!alreadyTyped) {
                    if (editable != null || isAnyInputFocused()) {
                        Action(ActionType.TYPE, element = editable, text = parsed.target, reason = "input ready", confidence = 0.95)
                    } else {
                        Action(ActionType.NONE, reason = "no editable field found", confidence = 0.3)
                    }
                } else {
                    val sendButton = elements.firstOrNull {
                        val d = (it.contentDescription ?: "").lowercase()
                        val t = (it.text ?: "").lowercase()
                        it.clickable && (d.contains("send") || t.contains("send"))
                    }
                    if (sendButton != null) {
                        Action(ActionType.TAP, element = sendButton, reason = "send button found", confidence = 0.9)
                    } else {
                        Action(ActionType.NONE, reason = "send button not found", confidence = 0.3)
                    }
                }
            }

            "tap", "open_chat" -> {
                val matches = elements.filter {
                    val t = (it.text ?: "").lowercase()
                    val d = (it.contentDescription ?: "").lowercase()
                    target.isNotBlank() && (t.contains(target) || d.contains(target))
                }
                if (matches.size == 1) {
                    Action(ActionType.TAP, element = matches.first(), reason = "single match for '$target'", confidence = 0.9)
                } else {
                    Action(ActionType.NONE, reason = "target '$target' unclear (${matches.size} matches)", confidence = 0.35)
                }
            }


            else -> Action(ActionType.NONE, reason = "unknown command", confidence = 0.2)
        }
    }

    // -----------------------------------------------------------------
    // Outcome verification - the fix for the "declares done instantly" bug
    // -----------------------------------------------------------------

    private fun computeSignature(elements: List<UiElement>): String {
        val textBlob = elements.joinToString("|") { (it.text ?: it.contentDescription ?: "").lowercase() }
        return "${currentForegroundPackage()}::${elements.size}::${textBlob.hashCode()}"
    }

    private fun isGoalAchieved(
        parsed: ParsedCommand,
        elements: List<UiElement>,
        previousAction: Action?,
        screenChanged: Boolean
    ): Boolean {
        val intent = parsed.intent
        val target = parsed.target.lowercase().trim()

        return when (intent) {
            "open_chat" -> {
                if (target.isBlank()) return false
                if (currentForegroundPackage() != resolveChatAppPackage()) return false

                val hasTarget = elements.any { (it.text ?: "").lowercase().contains(target) }
                val hasMessageInput = elements.any { it.editable }  // compose box only exists inside an actual chat

                hasTarget && hasMessageInput
            }

            "type_and_send" -> {
                val editable = elements.firstOrNull { it.editable }
                previousAction?.type == ActionType.TAP && (editable == null || (editable.text ?: "").isBlank())
            }

            "type" -> {
                if (target.isBlank()) return false
                elements.any { it.editable && (it.text ?: "").lowercase().contains(target) }
            }

            "search" -> {
                if (target.isBlank()) return false
                elements.any { it.editable && (it.text ?: "").lowercase().contains(target) }
            }

            "send" -> previousAction?.type == ActionType.TAP

            "tap" -> previousAction?.type == ActionType.TAP && screenChanged
            "scroll" -> previousAction?.type == ActionType.SWIPE && screenChanged
            "back" -> previousAction?.type == ActionType.BACK
            "home" -> previousAction?.type == ActionType.HOME

            else -> false
        }
    }

    // -----------------------------------------------------------------
    // Single unified executor
    // -----------------------------------------------------------------


    private fun executeAction(action: Action, elements: List<UiElement>, pressEnter: Boolean = false): Boolean {
        Log.d(TAG, "Executing ${action.type} source=${action.source}")

        return when (action.type) {
            ActionType.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            ActionType.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            ActionType.SWIPE -> performDirectionalSwipe(action.direction ?: "down")

            ActionType.TYPE -> {
                val text = action.text ?: return false
                if (action.element != null && isSensitiveElement(action.element)) {
                    warnSensitiveBlocked()
                    return false
                }
                val typed = performType(text)
                if (typed) return@executeAction true

                val point = resolveTapPoint(action)
                if (point != null) {
                    val tapped = performTap(point.first, point.second)
                    if (tapped) {
                        Thread.sleep(500)
                        return@executeAction performType(text)
                    }
                }
                false
            }

            ActionType.TAP -> {
                val point = resolveTapPoint(action) ?: return false
                if (action.element != null && isSensitiveElement(action.element)) {
                    warnSensitiveBlocked()
                    return false
                }
                performTap(point.first, point.second)
            }

            ActionType.TAP_THEN_TYPE -> {
                val point = resolveTapPoint(action) ?: return false
                if (action.element != null && isSensitiveElement(action.element)) {
                    warnSensitiveBlocked()
                    return false
                }
                val text = action.text ?: return false

                val tapped = performTap(point.first, point.second)
                if (!tapped) return false

                Thread.sleep(700)
                performType(text)
            }

            ActionType.WAIT -> {
                Thread.sleep(800)
                true
            }

            ActionType.DONE, ActionType.ASK_USER, ActionType.NONE -> false
        }
    }

    private fun resolveTapPoint(action: Action): Pair<Float, Float>? {
        action.element?.let { return Pair(it.centerX(), it.centerY()) }

        if (!action.gridCell.isNullOrBlank()) {
            val metrics = resources.displayMetrics
            gridCellToPoint(action.gridCell, metrics.widthPixels, metrics.heightPixels)?.let { return it }
        }

        if (action.x != null && action.y != null) {
            return Pair(action.x, action.y)
        }

        return null
    }

    private fun isSensitiveElement(element: UiElement): Boolean {
        val combined = ((element.text ?: "") + " " + (element.contentDescription ?: "")).lowercase()
        return sensitiveKeywords.any { combined.contains(it) }
    }

    private fun warnSensitiveBlocked() {
        val msg = "That looks like a sensitive action, so I'll let you do it yourself."
        showOverlayWithStatus(msg)
        ghostTts?.speak(msg, currentReplyLanguage)
    }

    // -----------------------------------------------------------------
    // VLM response parsing
    // -----------------------------------------------------------------
    private fun parseVlmAction(responseJson: String, elements: List<UiElement>): Action? {
        return try {
            val obj = JSONObject(responseJson)

            val actionStr = obj.optString("action")
            val gridCell = if (obj.isNull("grid_cell")) null else obj.optString("grid_cell")
            val rawText = if (obj.isNull("text")) null else obj.optString("text")
            val direction = if (obj.isNull("direction")) null else obj.optString("direction")
            val targetText = if (obj.isNull("target_text")) null else obj.optString("target_text")
            val reason = obj.optString("reason", "")
            val userMessage = if (obj.isNull("user_message")) null else obj.optString("user_message")
            val confidence = if (obj.isNull("confidence")) 0.8 else obj.optDouble("confidence", 0.8)
            val x = if (obj.isNull("x")) null else obj.optDouble("x").toFloat()
            val y = if (obj.isNull("y")) null else obj.optDouble("y").toFloat()

            // The model sometimes puts the actual text-to-type into target_text
            // instead of text (confusing "what to type" with "what to find").
            // Fall back so a type action never silently fails just because the
            // model picked the wrong field for the same information.
            val finalText = when {
                !rawText.isNullOrBlank() -> rawText
                !targetText.isNullOrBlank() -> targetText
                else -> null
            }

            val type = when (actionStr) {
                "tap" -> ActionType.TAP
                "type" -> ActionType.TYPE
                "swipe" -> ActionType.SWIPE
                "wait" -> ActionType.WAIT
                "done" -> ActionType.DONE
                "ask_user" -> ActionType.ASK_USER
                else -> ActionType.NONE
            }

            if (type in setOf(ActionType.TAP, ActionType.TYPE, ActionType.TAP_THEN_TYPE, ActionType.SWIPE) &&
                confidence < CONFIDENCE_FLOOR
            ) {
                return Action(
                    type = ActionType.ASK_USER,
                    reason = reason,
                    userMessage = userMessage ?: "I'm not fully sure - can you clarify?",
                    confidence = confidence,
                    source = "vlm"
                )
            }

            var resolvedElement: UiElement? = null

            if (type == ActionType.TAP || type == ActionType.TYPE) {
                val searchTerm = listOfNotNull(targetText, rawText, reason)
                    .map { it.lowercase().trim() }
                    .firstOrNull { it.isNotBlank() && it.length > 1 }

                if (searchTerm != null) {
                    val matches = elements.filter {
                        val t = (it.text ?: "").lowercase()
                        val d = (it.contentDescription ?: "").lowercase()
                        val isAvatarLike = d.contains("picture") || d.contains("photo") || d.contains("avatar")
                        !isAvatarLike && (t.contains(searchTerm) || d.contains(searchTerm) || (t.isNotBlank() && searchTerm.contains(t)))
                    }

                    resolvedElement = when {
                        matches.size == 1 -> matches.first()
                        matches.size > 1 -> matches.maxByOrNull { el ->
                            var score = 0
                            if (el.clickable) score += 100
                            score += (el.right - el.left)
                            score
                        }
                        else -> null
                    }
                }
            }

            var earlyAskUser: Action? = null
            if (type == ActionType.TAP && resolvedElement == null && x == null && y == null) {
                Log.d(TAG, "VLM tap: no text match and no coordinates available")
                earlyAskUser = Action(
                    type = ActionType.ASK_USER,
                    reason = "could not resolve tap target",
                    userMessage = userMessage ?: "I couldn't find that on screen - can you check?",
                    confidence = confidence,
                    source = "vlm"
                )
            }
            if (earlyAskUser != null) return earlyAskUser



            Action(
                type = type,
                element = resolvedElement,
                text = finalText,
                direction = direction,
                gridCell = gridCell,
                x = x,
                y = y,
                reason = reason,
                userMessage = userMessage,
                confidence = confidence,
                source = "vlm"
            )
        } catch (e: Exception) {
            Log.e(TAG, "parseVlmAction failed", e)
            null
        }
    }


    // -----------------------------------------------------------------
    // Screen reading
    // -----------------------------------------------------------------

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
        val targetWords = parsed.target.lowercase().split(" ").filter { it.length > 1 }
        val intent = parsed.intent

        val candidateElements = if (intent == "open_chat") {
            elements.filterNot {
                val d = (it.contentDescription ?: "").lowercase()
                d.contains("status") || d.contains("update") || d.contains("picture") || d.contains("photo") || d.contains("avatar")
            }
        } else {
            elements
        }

        val ranked = elements.sortedByDescending { element ->
            var score = 0
            val t = (element.text ?: "").lowercase()
            val d = (element.contentDescription ?: "").lowercase()

            if (element.editable) score += 100
            if (element.clickable) score += 40
            if (intent == "search" && (t.contains("search") || d.contains("search"))) score += 100
            targetWords.forEach { word -> if (t.contains(word) || d.contains(word)) score += 80 }

            score
        }.take(15)

        val arr = JSONArray()
        ranked.forEach { element ->
            val obj = JSONObject()
            obj.put("i", element.id)
            obj.put("t", element.text ?: "")
            obj.put("d", element.contentDescription ?: "")
            obj.put("b", JSONArray(listOf(element.left, element.top, element.right, element.bottom)))
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

    // -----------------------------------------------------------------
    // Gesture / input primitives
    // -----------------------------------------------------------------

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
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }

            val success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (!success) Log.e(TAG, "ACTION_SET_TEXT failed")
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

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    success = true; latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    success = false; latch.countDown()
                }
            }, mainHandler)

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
            "up" -> { startY = height * 0.75f; endY = height * 0.30f }
            "down" -> { startY = height * 0.30f; endY = height * 0.75f }
            "left" -> return performHorizontalSwipe(left = true)
            "right" -> return performHorizontalSwipe(left = false)
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

    private fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
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

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    success = true; latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    success = false; latch.countDown()
                }
            }, mainHandler)

            latch.await(3, TimeUnit.SECONDS)
            success
        } catch (e: Exception) {
            Log.e(TAG, "performSwipe failed", e)
            false
        }
    }

    private fun gridCellToPoint(gridCell: String, screenWidth: Int, screenHeight: Int): Pair<Float, Float>? {
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

    // -----------------------------------------------------------------
    // Screenshot capture
    // -----------------------------------------------------------------

    private fun captureScreenJpegBytes(): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.e(TAG, "takeScreenshot requires Android 11+")
            return null
        }

        val latch = CountDownLatch(1)
        var resultBytes: ByteArray? = null
        val executor = java.util.concurrent.Executor { runnable -> mainHandler.post(runnable) }

        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBuffer.close()

                        if (bitmap == null) {
                            Log.e(TAG, "Bitmap from screenshot is null")
                            latch.countDown()
                            return
                        }

                        val resizedBitmap = resizeBitmapForVlm(bitmap, maxWidth = 540)
                        val outputStream = ByteArrayOutputStream()
                        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 45, outputStream)
                        resultBytes = outputStream.toByteArray()

                        if (resizedBitmap != bitmap) resizedBitmap.recycle()
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
            })

            val completed = latch.await(5, TimeUnit.SECONDS)
            if (!completed) {
                Log.e(TAG, "Screenshot timed out")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureScreenJpegBytes failed", e)
            return null
        }

        return resultBytes
    }

    private fun resizeBitmapForVlm(bitmap: Bitmap, maxWidth: Int): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val scale = maxWidth.toFloat() / max(1, bitmap.width).toFloat()
        val newHeight = max(1, (bitmap.height * scale).toInt())
        return Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
    }
}