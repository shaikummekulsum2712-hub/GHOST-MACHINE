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
import android.provider.AlarmClock
import android.provider.CalendarContract
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
        private const val MAX_STEPS = 10
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

    // Side-effect guard: once a send/call action has actually been dispatched,
    // never blindly repeat it just because verification needs another scan.
    private var sideEffectKey: String? = null
    private var sideEffectDispatched = false
    private var typeAndSendHasTyped = false

    private val taskManager = TaskManager()
    private val actionVerifier = ActionVerifier()
    private val screenObserver by lazy { ScreenObserver(this) }

    sealed class StepResult {
        object Done : StepResult()
        data class NeedsHelp(val message: String) : StepResult()
        object Error : StepResult()
    }

    // -----------------------------------------------------------------
    // Data model
    // -----------------------------------------------------------------

    data class UiElement(
        val id: Int,
        val text: String?,
        val contentDescription: String?,
        val resourceId: String?,
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
        TAP, TAP_THEN_TYPE, TYPE, SEND, SWIPE, BACK, HOME, WAIT, DONE, ASK_USER, NONE
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
                val errorName = when (error) {
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
                    SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                    SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
                    SpeechRecognizer.ERROR_SERVER -> "SERVER"
                    SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT (didn't hear anything)"
                    SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH (heard audio, couldn't transcribe)"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
                    else -> "UNKNOWN($error)"
                }
                Log.e(TAG, "Speech error: $errorName")
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    setOverlayStatus(VoiceLanguageManager.message("need_help", currentReplyLanguage))
                } else {
                    setOverlayStatus("Voice input had a problem. Please try again.")
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val command = matches?.firstOrNull()?.trim()

                if (command.isNullOrBlank()) {
                    val msg = VoiceLanguageManager.message("need_help", currentReplyLanguage)
                    setOverlayStatus(msg)
                    ghostTts?.speak(msg, currentReplyLanguage)
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000)
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

        sideEffectKey = null
        sideEffectDispatched = false
        typeAndSendHasTyped = false

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

        val parsed = repairParsedCommand(voiceContext)
        Log.d(TAG, "Parsed command: intent=${parsed.intent}, target=${parsed.target}")

        if (!isCommandSafe(voiceContext.originalCommand, parsed)) {
            taskManager.cancel()
            speakAndFinish("need_help", currentReplyLanguage, overrideStatus = "I can't perform that sensitive action.")
            isRunning.set(false)
            return
        }

        taskManager.begin(voiceContext.originalCommand, parsed.intent, parsed.target)

        if (parsed.intent == "stop") {
            taskManager.cancel()
            isRunning.set(false)
            showButtonAgain()
            return
        }

        if (parsed.intent == "direct_message") {
            Thread {
                try {
                    runDirectMessage(parsed.target, currentReplyLanguage)
                } catch (e: Exception) {
                    Log.e(TAG, "Direct message workflow failed", e)
                    speakAndFinish("error", currentReplyLanguage)
                } finally {
                    taskManager.finish()
                    isRunning.set(false)
                }
            }.start()
            return
        }

        val nativeBeforePackage = currentForegroundPackage()
        val nativeHandled = handleNativeCapability(voiceContext.normalizedCommand, parsed)
        if (nativeHandled) {
            mainHandler.postDelayed({
                if (verifyNativeGoal(parsed, nativeBeforePackage)) {
                    taskManager.success()
                    taskManager.finish()
                    showButtonAgain()
                    isRunning.set(false)
                } else {
                    Log.w(TAG, "Native capability dispatched but goal was not verified; entering UI recovery")
                    Thread {
                        try {
                            runVisionLoop(voiceContext.normalizedCommand, parsed, currentReplyLanguage)
                        } finally {
                            taskManager.finish()
                            isRunning.set(false)
                        }
                    }.start()
                }
            }, 1200)
            return
        }

        if (handleDirectOpenCommand(voiceContext.normalizedCommand, parsed)) {
            mainHandler.postDelayed({
                val verified = parsed.intent == "open" &&
                        findPackageByAppName(parsed.target)?.let { currentForegroundPackage() == it } == true
                if (verified) {
                    taskManager.success()
                    taskManager.finish()
                    showButtonAgain()
                    isRunning.set(false)
                } else {
                    Log.w(TAG, "Open command dispatched but foreground verification failed")
                    Thread {
                        try {
                            runVisionLoop(voiceContext.normalizedCommand, parsed, currentReplyLanguage)
                        } finally {
                            taskManager.finish()
                            isRunning.set(false)
                        }
                    }.start()
                }
            }, APP_LAUNCH_DELAY_MS)
            return
        }

        Thread {
            try {
                if (looksCompound(voiceContext.normalizedCommand)) {
                    Log.d(TAG, "normalizedCommand for Jev compound loop: ${voiceContext.normalizedCommand}")
                    runMultiStepCommand(voiceContext.normalizedCommand, currentReplyLanguage)
                } else {
                    runVisionLoop(voiceContext.normalizedCommand, parsed, currentReplyLanguage)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Vision loop crashed", e)
                speakAndFinish("error", currentReplyLanguage)
            } finally {
                taskManager.finish()
                isRunning.set(false)
            }
        }.start()
    }

    private fun containsSensitiveText(value: String): Boolean {
        val normalized = value.lowercase(Locale.getDefault())
        return sensitiveKeywords.any { keyword ->
            Regex("(?<![a-z0-9])" + Regex.escape(keyword.lowercase()) + "(?![a-z0-9])")
                .containsMatchIn(normalized)
        }
    }

    private fun isCommandSafe(command: String, parsed: ParsedCommand): Boolean {
        val blocked = containsSensitiveText(command)
        if (blocked) Log.w(TAG, "Blocked sensitive command: ${parsed.intent}")
        return !blocked
    }

    private fun runDirectMessage(payload: String, replyLanguage: String) {
        val separator = payload.indexOf("|||")
        if (separator <= 0 || separator >= payload.length - 3) {
            speakAndFinish("need_help", replyLanguage, overrideStatus = "I need the contact and message.")
            return
        }
        val target = payload.substring(0, separator).trim()
        val message = payload.substring(separator + 3).trim()
        if (target.isBlank() || message.isBlank()) {
            speakAndFinish("need_help", replyLanguage, overrideStatus = "I need the contact and message.")
            return
        }
        val open = runVisionLoop("open chat $target", ParsedCommand("open_chat", target), replyLanguage, false)
        if (open !is StepResult.Done) return
        runVisionLoop(message, ParsedCommand("type_and_send", message), replyLanguage, true)
    }

    private fun repairParsedCommand(context: VoiceLanguageManager.VoiceCommandContext): ParsedCommand {
        val resolved = GhostIntentResolver.resolve(context.originalCommand)
        if (resolved.intent != "unknown") return ParsedCommand(resolved.intent, resolved.target)
        val normalized = context.normalizedCommand.trim()

        fun normalizeTarget(raw: String): String {
            return raw.trim()
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        // Preserve multi-word entity names such as "swipe loan". "swipe",
        // "open", and "go" are only action words when they form a complete
        // command; they are not stripped from an entity name.
        fun extractChatTarget(text: String): String? {
            val match = Regex(
                "^(?:open\\s+(?:a\\s+)?chat|message|text|chat)\\s+(.+?)(?:\\s+and\\s+(?:message|text|send|type|call)\\b.*)?$",
                RegexOption.IGNORE_CASE
            ).find(text) ?: return null
            return normalizeTarget(match.groupValues.getOrNull(1).orEmpty()).ifBlank { null }
        }

        val explicitChatTarget = extractChatTarget(normalized)
        if (explicitChatTarget != null) {
            return ParsedCommand("open_chat", explicitChatTarget)
        }

        // The language parser can sometimes over-normalize a compound target.
        // Recover the phrase immediately after "open chat" before the next
        // command boundary, without stripping words such as "swipe".
        Regex("\\bopen\\s+(?:a\\s+)?chat\\s+(.+?)(?=\\s+and\\s+(?:message|text|send|type|call)\\b|$)", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.getOrNull(1)?.trim()?.let {
                if (it.isNotBlank()) return ParsedCommand("open_chat", normalizeTarget(it))
            }

        if (context.parsedIntent != "unknown" && context.parsedTarget.isNotBlank()) {
            return ParsedCommand(context.parsedIntent, normalizeTarget(context.parsedTarget))
        }

        return when {
            Regex("^open\\s+(.+)$", RegexOption.IGNORE_CASE).find(normalized) != null -> {
                val target = Regex("^open\\s+(.+)$", RegexOption.IGNORE_CASE)
                    .find(normalized)?.groupValues?.getOrNull(1).orEmpty()
                ParsedCommand("open", normalizeTarget(target))
            }
            Regex("^(?:search|find|look(?:\\s+up)?)\\s+(?:for\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(normalized) != null -> {
                val target = Regex("^(?:search|find|look(?:\\s+up)?)\\s+(?:for\\s+)?(.+)$", RegexOption.IGNORE_CASE)
                    .find(normalized)?.groupValues?.getOrNull(1).orEmpty()
                ParsedCommand("search", normalizeTarget(target))
            }
            Regex("^call\\s+(.+)$", RegexOption.IGNORE_CASE).find(normalized) != null -> {
                val target = Regex("^call\\s+(.+)$", RegexOption.IGNORE_CASE)
                    .find(normalized)?.groupValues?.getOrNull(1).orEmpty()
                ParsedCommand("call", normalizeTarget(target))
            }
            Regex("^type\\s+(.+)$", RegexOption.IGNORE_CASE).find(normalized) != null -> {
                val target = Regex("^type\\s+(.+)$", RegexOption.IGNORE_CASE)
                    .find(normalized)?.groupValues?.getOrNull(1).orEmpty()
                ParsedCommand("type", normalizeTarget(target))
            }
            normalized == "back" || normalized == "go back" -> ParsedCommand("back", "")
            normalized == "home" || normalized.contains("go home") -> ParsedCommand("home", "")
            Regex("^(?:scroll|swipe)\\s+(up|down|left|right)$", RegexOption.IGNORE_CASE).find(normalized) != null -> {
                val direction = Regex("^(?:scroll|swipe)\\s+(up|down|left|right)$", RegexOption.IGNORE_CASE)
                    .find(normalized)?.groupValues?.getOrNull(1).orEmpty()
                ParsedCommand("scroll", direction)
            }
            else -> ParsedCommand(context.parsedIntent, normalizeTarget(context.parsedTarget))
        }
    }

    private fun looksCompound(command: String): Boolean {
        return GhostIntentResolver.resolve(command).isCompound
    }

    // -----------------------------------------------------------------
    // Direct app-open shortcuts (single-shot, no follow-up UI interaction)
    // -----------------------------------------------------------------

    private fun handleNativeCapability(command: String, parsed: ParsedCommand): Boolean {
        if (looksCompound(command)) return false

        return NativeCapabilities.handle(this, command) {
            mainHandler.postDelayed({
                val elements = collectScreenElements()
                val shutter = elements.firstOrNull {
                    val t = (it.text ?: "").lowercase()
                    val d = (it.contentDescription ?: "").lowercase()
                    it.clickable && (d.contains("shutter") || d.contains("take photo") ||
                            d.contains("take picture") || d.contains("capture") ||
                            t == "take photo" || t == "take picture")
                }
                if (shutter != null && performTap(shutter.centerX(), shutter.centerY())) {
                    setOverlayStatus("Picture taken")
                } else {
                    setOverlayStatus("Camera opened; capture button not found")
                }
            }, APP_LAUNCH_DELAY_MS)
        }
    }

    private fun handleDirectOpenCommand(command: String, parsed: ParsedCommand): Boolean {
        if (parsed.intent == "open_chat") return false

        val lower = command.lowercase(Locale.getDefault()).trim()
        if (!lower.startsWith("open ")) return false

        // A direct open is only valid for a standalone open command. If the
        // command contains another operation, let the multistep planner run.
        if (looksCompound(lower)) return false

        val appName = lower.removePrefix("open ").trim()
        if (appName.isBlank()) return false

        if (appName == "settings" || appName == "system settings") {
            return openSettings()
        }

        val packageName = findPackageByAppName(appName)
        if (packageName != null) {
            if (currentForegroundPackage() == packageName) {
                setOverlayStatus("App is already open")
                return true
            }
            return openApp(packageName)
        }

        return false
    }

    private fun openSettings(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            setOverlayStatus("Opening settings")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Open settings failed", e)
            false
        }
    }

    private fun openApp(packageName: String): Boolean {
        return try {
            if (currentForegroundPackage() == packageName) {
                setOverlayStatus("App is already open")
                return true
            }

            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                setOverlayStatus("App cannot be opened")
                return false
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            setOverlayStatus("Opening app...")

            mainHandler.postDelayed({
                if (currentForegroundPackage() == packageName) {
                    setOverlayStatus("Opened app")
                } else {
                    setOverlayStatus("App did not open")
                }
            }, APP_LAUNCH_DELAY_MS)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Open app failed: $packageName", e)
            setOverlayStatus("Could not open app")
            false
        }
    }

    private fun findPackageByAppName(appName: String): String? {
        val wanted = normalizeAppName(appName)
        if (wanted.isBlank()) return null

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val launchablePackages = packageManager.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName }
            .toSet()

        val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != packageName }

        val candidates = apps.mapNotNull { app ->
            val label = packageManager.getApplicationLabel(app).toString()
            val normalizedLabel = normalizeAppName(label)
            if (normalizedLabel.isBlank()) return@mapNotNull null

            val launchable = app.packageName in launchablePackages ||
                    packageManager.getLaunchIntentForPackage(app.packageName) != null
            if (!launchable) return@mapNotNull null

            val score = when {
                normalizedLabel == wanted -> 100
                normalizedLabel.startsWith("$wanted ") -> 90
                normalizedLabel.contains(wanted) -> 80
                wanted.contains(normalizedLabel) -> 75
                else -> 0
            }

            if (score == 0) null else Pair(app.packageName, score)
        }

        return candidates.maxByOrNull { it.second }?.first
    }

    private fun normalizeAppName(value: String): String {
        return value.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun currentForegroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    private fun ensureAppOpen(packageName: String): Boolean {
        if (currentForegroundPackage() == packageName) return true
        return openApp(packageName)
    }

    private fun resolveChatAppPackage(): String {
        return findPackageByAppName("WhatsApp") ?: "com.whatsapp"
    }

    private fun resolveCallAppPackage(): String {
        val dialIntent = Intent(Intent.ACTION_DIAL)
        val resolved = packageManager.resolveActivity(dialIntent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName ?: findPackageByAppName("Phone").orEmpty()
    }

    private fun openSystemDialer(): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            setOverlayStatus("Opening phone")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Open system dialer failed", e)
            false
        }
    }

    private fun isSearchElement(element: UiElement): Boolean {
        val t = (element.text ?: "").lowercase()
        val d = (element.contentDescription ?: "").lowercase()
        if (t.contains("voice") || d.contains("voice") || d.contains("microphone") ||
            d.contains("search by voice") || t.contains("search by voice")) return false
        return t.contains("search") ||
                d.contains("search") ||
                d.contains("search or type") ||
                d.contains("find")
    }

    private fun isAppDrawerSearch(element: UiElement): Boolean {
        val t = (element.text ?: "").lowercase()
        val d = (element.contentDescription ?: "").lowercase()
        return d.contains("apps") || d.contains("play store") ||
                d.contains("games") || t.contains("apps & games")
    }

    private fun findSearchElement(elements: List<UiElement>): UiElement? {
        val explicit = elements.firstOrNull {
            !isAppDrawerSearch(it) && isSearchElement(it) && (it.editable || it.clickable)
        }
        if (explicit != null) return explicit

        val pkg = currentForegroundPackage().orEmpty()
        val knownSearchApp = pkg in setOf(
            "com.android.chrome",
            "com.google.android.youtube",
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.messaging",
            "com.whatsapp"
        )
        return if (knownSearchApp) {
            elements.firstOrNull {
                it.editable && !isAppDrawerSearch(it) && isLikelySearchInput(it)
            }
        } else {
            null
        }
    }

    private fun isLikelySearchInput(element: UiElement): Boolean {
        val t = (element.text ?: "").lowercase()
        val d = (element.contentDescription ?: "").lowercase()
        return t.isBlank() || t.contains("search") || d.contains("search") ||
                d.contains("type to search") || d.contains("search or type")
    }

    private fun hasUsableSearchField(elements: List<UiElement>): Boolean {
        return findSearchElement(elements) != null
    }

    private fun findCallButton(elements: List<UiElement>): UiElement? {
        return elements.firstOrNull {
            val t = (it.text ?: "").lowercase()
            val d = (it.contentDescription ?: "").lowercase()
            it.clickable && (
                    d == "call" || d.contains("call mobile") ||
                            d.contains("voice call") || d.contains("audio call") ||
                            d.contains("call") || t == "call"
                    ) && !d.contains("video")
        }
    }

    private fun findSendButton(elements: List<UiElement>): UiElement? {
        return elements.firstOrNull {
            val t = (it.text ?: "").trim().lowercase()
            val d = (it.contentDescription ?: "").trim().lowercase()
            val r = (it.resourceId ?: "").trim().lowercase()
            it.clickable && !isSensitiveElement(it) && (
                    d == "send" || t == "send" ||
                            d.contains("send") || t.contains("send") ||
                            r.contains("send")
                    ) && !d.contains("money") && !d.contains("voice") && !r.contains("money")
        }
    }

    private fun normalizeTargetVariants(value: String): List<String> {
        val cleaned = value.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isBlank()) return emptyList()
        return listOf(cleaned, cleaned.replace(" ", "")).distinct()
    }

    private fun normalizeName(value: String): String {
        return value.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    private fun nameMatches(element: UiElement, target: String): Boolean {
        val wantedVariants = normalizeTargetVariants(target).map { normalizeName(it) }.filter { it.isNotBlank() }
        if (wantedVariants.isEmpty()) return false

        val candidates = listOfNotNull(element.text, element.contentDescription)
            .map { normalizeName(it) }
            .filter { it.isNotBlank() }

        if (candidates.any { candidate -> wantedVariants.any { wanted ->
                candidate == wanted || candidate.contains(wanted) || wanted.contains(candidate)
            }}) return true

        val wanted = wantedVariants.first()
        val wantedParts = wanted.split(" ").filter { it.length >= 3 }
        if (wantedParts.isEmpty()) return false

        return candidates.any { candidate ->
            val parts = candidate.split(" ").filter { it.length >= 3 }
            wantedParts.any { w ->
                parts.any { c ->
                    val maxDistance = if (w.length <= 5) 1 else 2
                    levenshtein(w, c) <= maxDistance
                }
            }
        }
    }

    private fun currentScreenCanCall(elements: List<UiElement>): Boolean {
        return findCallButton(elements) != null
    }

    private fun currentScreenIsTargetChat(elements: List<UiElement>, target: String): Boolean {
        val messageInput = elements.firstOrNull { element ->
            if (!element.editable || isSearchElement(element)) return@firstOrNull false
            val t = (element.text ?: "").lowercase()
            val d = (element.contentDescription ?: "").lowercase()
            val r = (element.resourceId ?: "").lowercase()
            t.contains("message") || d.contains("message") || r.contains("message") || r.contains("compose") || r.contains("chat")
        } ?: elements.firstOrNull { it.editable && !isSearchElement(it) }
        if (messageInput == null) return false

        val hasChatControl = findSendButton(elements) != null || findCallButton(elements) != null ||
                elements.any {
                    val d = (it.contentDescription ?: "").lowercase()
                    d.contains("video call") || d.contains("chat info") || d.contains("conversation")
                }
        if (!hasChatControl) return false
        if (target.isBlank()) return true

        val headerMatch = elements.any { element ->
            val t = (element.text ?: "").trim()
            val d = (element.contentDescription ?: "").trim()
            val headerLike = element.top < resources.displayMetrics.heightPixels * 0.25f &&
                    !element.editable && !isSearchElement(element) && !isAvatarLike(element)
            headerLike && (nameMatches(element, target) || t.equals(target, true) || d.equals(target, true))
        }
        return headerMatch
    }

    private fun isAvatarLike(element: UiElement): Boolean {
        val d = (element.contentDescription ?: "").lowercase()
        return d.contains("picture") || d.contains("photo") || d.contains("avatar")
    }

    private fun performImeSend(): Boolean {
        val focused = try { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
        if (focused != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val sent = focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                if (sent) return true
            } catch (e: Exception) {
                Log.e(TAG, "IME send failed", e)
            }
        }
        Log.w(TAG, "No send button or IME send action available; ADB keyevent 66 cannot be executed by a normal app sandbox")
        return false
    }

    private fun performImeEnter(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val imeSuccess = try {
                focused?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) ?: false
            } catch (e: Exception) {
                Log.e(TAG, "ACTION_IME_ENTER failed", e)
                false
            }
            if (imeSuccess) return true
        }

        val elements = collectScreenElements()
        val explicitButton = elements.firstOrNull {
            val t = (it.text ?: "").trim().lowercase()
            val d = (it.contentDescription ?: "").trim().lowercase()
            it.clickable && !isSensitiveElement(it) && (
                    t in setOf("search", "go", "submit", "find", "done") ||
                            d in setOf("search", "go", "submit", "find", "done")
                    )
        }

        return if (explicitButton != null) {
            Log.d(TAG, "IME_ENTER unsupported, tapping explicit submit button")
            performTap(explicitButton.centerX(), explicitButton.centerY())
        } else {
            false
        }
    }

    private fun describeCurrentPhoneState(elements: List<UiElement>): String {
        val packageName = currentForegroundPackage().orEmpty()
        val visible = elements.asSequence()
            .filter { it.clickable || it.editable || !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank() }
            .take(35)
            .map {
                val text = it.text?.trim().orEmpty()
                val desc = it.contentDescription?.trim().orEmpty()
                val resource = it.resourceId?.trim().orEmpty()
                listOf(text, desc, resource).filter { value -> value.isNotBlank() }.joinToString(" | ")
            }
            .filter { it.isNotBlank() }
            .toList()

        return buildString {
            append("Foreground package: ").append(packageName).append('\n')
            append("Visible UI elements:\n")
            visible.forEach { append("- ").append(it).append('\n') }
            if (visible.isEmpty()) append("- No readable UI elements")
        }
    }

    private fun runMultiStepCommand(command: String, replyLanguage: String) {
        var lastAction: String? = null
        var completedAtLeastOneStep = false

        for (round in 1..MAX_STEPS) {
            val elements = collectScreenElements()
            val state = describeCurrentPhoneState(elements)

            Log.d(TAG, "===== JEV ROUND $round/$MAX_STEPS =====")
            Log.d(TAG, "Jev state:\n$state")

            mainHandler.post { setOverlayStatus("Jev deciding step $round...") }

            val plan = when (val result = ApiClient.planCommand(
                this@GhostAccessibilityService,
                command,
                replyLanguage,
                currentState = state,
                lastAction = lastAction
            )) {
                is ApiClient.PlanResult.Success -> result
                is ApiClient.PlanResult.Failure -> {
                    Log.e(TAG, "Jev planner failed")
                    speakAndFinish("error", replyLanguage, overrideStatus = "Planner unavailable")
                    return
                }
                else -> {
                    speakAndFinish("error", replyLanguage, overrideStatus = "Planner unavailable")
                    return
                }
            }

            val step = plan.steps.firstOrNull() ?: run {
                speakAndFinish("need_help", replyLanguage, overrideStatus = "I couldn't decide the next safe step.")
                return
            }

            Log.d(TAG, "Jev selected: ${step.intent} -> ${step.target}")

            if (step.intent == "task_finished") {
                if (completedAtLeastOneStep) {
                    speakAndFinish("done", replyLanguage)
                } else {
                    speakAndFinish("need_help", replyLanguage, overrideStatus = "The planner finished without executing a step.")
                }
                return
            }

            if (step.intent == "ask_user") {
                speakAndFinish("need_help", replyLanguage, overrideStatus = step.target.ifBlank { "I need your help to continue." })
                return
            }

            val parsedStep = ParsedCommand(step.intent, step.target)
            val result = runVisionLoop(
                step.target,
                parsedStep,
                replyLanguage,
                speakOnSuccess = false
            )

            when (result) {
                is StepResult.Done -> {
                    completedAtLeastOneStep = true
                    lastAction = "${step.intent}:${step.target}"
                    mainHandler.post { setOverlayStatus("Step complete") }
                    Thread.sleep(STEP_DELAY_MS)
                }
                is StepResult.NeedsHelp, is StepResult.Error -> {
                    Log.d(TAG, "Jev chain stopped after ${step.intent}: ${result}")
                    return
                }
            }
        }

        speakAndFinish("need_help", replyLanguage, overrideStatus = "I reached the safe step limit before finishing.")
    }

    private fun runVisionLoop(
        command: String,
        parsed: ParsedCommand,
        replyLanguage: String,
        speakOnSuccess: Boolean = true
    ): StepResult {
        Log.d(TAG, "==============================")
        Log.d(TAG, "NEW COMMAND: $command | intent=${parsed.intent} target=${parsed.target}")
        Log.d(TAG, "==============================")

        if (parsed.intent in setOf("search", "type", "type_and_send") && parsed.target.isBlank()) {
            speakAndFinish("need_help", replyLanguage, overrideStatus = "What would you like me to say or search for?")
            return StepResult.NeedsHelp("blank target")
        }

        if (parsed.intent == "call") {
            val currentElements = collectScreenElements()
            val currentPkg = currentForegroundPackage()
            val currentCanCall = currentScreenCanCall(currentElements)
            val targetVisible = parsed.target.isNotBlank() &&
                    currentElements.any { nameMatches(it, parsed.target) && !isAvatarLike(it) }
            val currentAppCanContinue = currentPkg == resolveChatAppPackage() ||
                    currentPkg == resolveCallAppPackage()

            // First rule: a callable control already on screen always wins.
            // If not, only stay in a known calling/messaging app when the
            // requested person is visible there. Otherwise open Phone.
            if (!currentCanCall && !(currentAppCanContinue && targetVisible)) {
                mainHandler.post { setOverlayStatus(VoiceLanguageManager.message("checking", replyLanguage)) }
                val launched = openSystemDialer()
                if (!launched) {
                    val callPackage = resolveCallAppPackage()
                    if (callPackage.isBlank() || !ensureAppOpen(callPackage)) {
                        speakAndFinish("error", replyLanguage)
                        return StepResult.Error
                    }
                }
                Thread.sleep(APP_LAUNCH_DELAY_MS)
            }
        }

        if (parsed.intent == "open") {
            val resolvedPackage = findPackageByAppName(parsed.target)

            if (resolvedPackage != null && currentForegroundPackage() != resolvedPackage) {
                mainHandler.post { setOverlayStatus(VoiceLanguageManager.message("checking", replyLanguage)) }
                val launched = ensureAppOpen(resolvedPackage)
                if (!launched) { speakAndFinish("error", replyLanguage); return StepResult.Error }
                Thread.sleep(APP_LAUNCH_DELAY_MS)
            }
        }

        if (parsed.intent == "open_chat") {
            val targetPackage = resolveChatAppPackage()
            val currentElements = collectScreenElements()
            val alreadyAchievable = isGoalAchieved(parsed, currentElements, null, false)

            if (!alreadyAchievable) {
                mainHandler.post { setOverlayStatus(VoiceLanguageManager.message("checking", replyLanguage)) }
                val launched = ensureAppOpen(targetPackage)
                if (!launched) {
                    speakAndFinish("error", replyLanguage)
                    return StepResult.Error
                }
                Thread.sleep(APP_LAUNCH_DELAY_MS)
            }
        }

        // Search: only auto-launch Google if nothing app-like is open. If
        // already inside some app, search that app's own search field instead
        // of forcing Google - decideWithAndroidOnly's "search" branch looks at
        // whatever screen is currently in front of it.
        if (parsed.intent == "search") {
            val currentElements = collectScreenElements()
            Log.d(TAG, "Search precondition package=${currentForegroundPackage()} elements=${currentElements.size}")

            // Search the app that is already on screen when it exposes a real
            // search field. Do not confuse a chat/message EditText with search.
            if (!hasUsableSearchField(currentElements)) {
                mainHandler.post { setOverlayStatus(VoiceLanguageManager.message("checking", replyLanguage)) }
                val launched = ensureAppOpen(findPackageByAppName("Google") ?: "com.google.android.googlequicksearchbox")
                if (!launched) { speakAndFinish("error", replyLanguage); return StepResult.Error }
                Thread.sleep(APP_LAUNCH_DELAY_MS)
            }
        }

        if (parsed.intent == "open_chat") {
            val currentElements = collectScreenElements()
            val currentPkg = currentForegroundPackage()

            if (currentScreenIsTargetChat(currentElements, parsed.target)) {
                Log.d(TAG, "Target chat already open; staying in current app")
            } else if (currentPkg != resolveChatAppPackage()) {
                mainHandler.post { setOverlayStatus(VoiceLanguageManager.message("checking", replyLanguage)) }
                if (!ensureAppOpen(resolveChatAppPackage())) {
                    speakAndFinish("error", replyLanguage)
                    return StepResult.Error
                }
                Thread.sleep(APP_LAUNCH_DELAY_MS)
            }
        }

        var previousAction: Action? = null
        var previousSignature: String? = null
        var repeatedFailCount = 0
        var lastAttemptedTarget: String? = null
        var vlmAskUserRetries = 0

        for (step in 1..MAX_STEPS) {
            Log.d(TAG, "===== STEP $step =====")
            mainHandler.post { setOverlayStatus(VoiceLanguageManager.message("checking", replyLanguage)) }

            val elements = collectScreenElements()
            val signature = computeSignature(elements)
            val screenChanged = previousSignature != null && signature != previousSignature
            if (isSensitiveScreen(elements)) {
                val msg = "This screen appears sensitive, so I won't automate actions here."
                Log.w(TAG, msg)
                showOverlayWithStatus(msg)
                ghostTts?.speak(msg, replyLanguage)
                showButtonAgain()
                return StepResult.NeedsHelp(msg)
            }
            val observed = screenObserver.observe()
            taskManager.observe(observed.packageName, observed.signature, observed.packageName, observed.screenType)

            Log.d(TAG, "Collected ${elements.size} elements, changed=$screenChanged")

            if (isGoalAchieved(parsed, elements, previousAction, screenChanged)) {
                Log.d(TAG, "Goal achieved")
                taskManager.success()
                if (speakOnSuccess) {
                    speakAndFinish("done", replyLanguage)
                } else {
                    mainHandler.post { setOverlayStatus("Step done") }
                }
                return StepResult.Done
            }

            val currentTargetKey = "${parsed.intent}:${parsed.target}"
            if (previousAction != null && !screenChanged && lastAttemptedTarget == currentTargetKey) {
                // Side-effect verification gets a little more time. Never turn
                // an already-dispatched send/call into another send/call.
                if (sideEffectDispatched && parsed.intent in setOf("type_and_send", "call")) {
                    Thread.sleep(900)
                }
                repeatedFailCount++
            } else {
                repeatedFailCount = 0
            }
            lastAttemptedTarget = currentTargetKey

            if (repeatedFailCount >= 3 || taskManager.shouldStop()) {
                if (sideEffectDispatched && parsed.intent in setOf("type_and_send", "call")) {
                    val msg = if (parsed.intent == "type_and_send") {
                        "I sent it, but I couldn't verify the result on screen."
                    } else {
                        "I started the call, but I couldn't verify the call state."
                    }
                    showOverlayWithStatus(msg)
                    ghostTts?.speak(msg, replyLanguage)
                    showButtonAgain()
                    return StepResult.NeedsHelp(msg)
                }
                Log.d(TAG, "Same action attempted repeatedly with no screen change - escalating to ask_user")
                speakAndFinish("need_help", replyLanguage, overrideStatus = "I couldn't complete that from the current screen.")
                return StepResult.NeedsHelp("stalled")
            }

            val lastActionHadNoEffect = previousAction != null &&
                    previousAction.type in setOf(
                ActionType.TAP, ActionType.TAP_THEN_TYPE, ActionType.SWIPE, ActionType.TYPE, ActionType.SEND
            ) && !screenChanged

            val decision: Action? =
                if (sideEffectDispatched && parsed.intent in setOf("type_and_send", "call")) {
                    Action(ActionType.WAIT, reason = "side effect dispatched; verify without repeating", confidence = 1.0)
                } else if (!lastActionHadNoEffect) {
                    decideWithAndroidOnly(parsed, elements)
                } else null

            Log.d(TAG, "Android decision: ${decision?.type} conf=${decision?.confidence} reason=${decision?.reason ?: "skipped (last action had no effect)"}")

            if (decision != null && decision.confidence >= CONFIDENCE_FLOOR) {
                val executed = executeAction(decision, elements, pressEnter = parsed.intent == "search")
                Log.d(TAG, "Android executed = $executed")

                if (executed) {
                    if (parsed.intent == "type_and_send" && decision.type == ActionType.TYPE) {
                        val verifiedTyped = actionVerifier.textInEditable(collectScreenElements(), parsed.target)
                        typeAndSendHasTyped = verifiedTyped
                        if (!verifiedTyped) taskManager.failure()
                    }
                    if (parsed.intent == "type_and_send" && isSendDispatch(decision)) {
                        sideEffectKey = "send:${currentForegroundPackage()}:${normalizeName(parsed.target)}"
                        sideEffectDispatched = true
                        taskManager.markSideEffect()
                    }
                    if (parsed.intent == "call" && decision.type == ActionType.TAP && decision.reason.contains("call", ignoreCase = true)) {
                        sideEffectKey = "call:${currentForegroundPackage()}:${normalizeName(parsed.target)}"
                        sideEffectDispatched = true
                        taskManager.markSideEffect()
                    }
                    taskManager.action(decision.type.name, signature)
                    previousAction = decision
                    previousSignature = signature
                    Thread.sleep(STEP_DELAY_MS)
                    continue
                }
                Log.d(TAG, "Android execution failed, falling back to VLM")
            }

            mainHandler.post { setOverlayStatus(VoiceLanguageManager.message("thinking", replyLanguage)) }

            hideOverlayForScreenshot()
            val screenshotBytes = captureScreenJpegBytes()
            mainHandler.post { overlayView?.visibility = View.VISIBLE }

            if (screenshotBytes == null) {
                Log.e(TAG, "Screenshot capture failed")
                break
            }

            val result = ApiClient.analyzeScreen(
                context = this@GhostAccessibilityService,
                command = command,
                screenshotBytes = screenshotBytes,
                screenElementsJson = elementsToJson(elements, parsed),
                parsedIntent = parsed.intent,
                parsedTarget = parsed.target,
                androidUncertainty = if (vlmAskUserRetries > 0) {
                    "Previous VLM answer asked for help without acting. Re-check the current screen and choose one concrete safe action if possible."
                } else {
                    decision?.reason ?: "no confident android decision"
                },
                previousAction = previousAction?.type?.name?.lowercase(),
                replyLanguage = replyLanguage
            )

            when (result) {
                is ApiClient.AnalyzeResult.NetworkError -> {
                    Log.e(TAG, "Network error: ${result.message}")
                    speakAndFinish("error", replyLanguage, overrideStatus = "Backend unreachable")
                    return StepResult.Error
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
                        // Do not stop on the first VLM "ask_user". Re-check the
                        // current screen with the deterministic Android rules first.
                        val recovery = decideWithAndroidOnly(parsed, elements)
                        if (recovery.type !in setOf(ActionType.NONE, ActionType.ASK_USER, ActionType.DONE) &&
                            recovery.confidence >= CONFIDENCE_FLOOR &&
                            !(sideEffectDispatched && parsed.intent in setOf("type_and_send", "call"))) {
                            val recovered = executeAction(
                                recovery,
                                elements,
                                pressEnter = parsed.intent == "search"
                            )
                            if (recovered) {
                                previousAction = recovery
                                previousSignature = signature
                                vlmAskUserRetries = 0
                                Thread.sleep(STEP_DELAY_MS)
                                continue
                            }
                        }

                        // A VLM can ask for help because it was uncertain for one
                        // transient response. Give it two re-observe/reason chances
                        // before surfacing the request to the user.
                        if (vlmAskUserRetries < 2) {
                            vlmAskUserRetries++
                            previousAction = null
                            previousSignature = signature
                            Thread.sleep(600)
                            continue
                        }

                        val msg = vlmAction.userMessage
                            ?: VoiceLanguageManager.message("need_help", replyLanguage, vlmAction.reason)
                        showOverlayWithStatus(msg)
                        ghostTts?.speak(msg, replyLanguage)
                        showButtonAgain()
                        return StepResult.NeedsHelp(msg)
                    }

                    if (vlmAction.type == ActionType.DONE) {
                        val verified = isGoalAchieved(parsed, elements, previousAction, false)
                        if (verified) {
                            if (speakOnSuccess) speakAndFinish("done", replyLanguage)
                            return StepResult.Done
                        }
                        Log.w(TAG, "VLM reported DONE but Android verification failed")
                        break
                    }

                    val executed = executeAction(vlmAction, elements, pressEnter = parsed.intent == "search")
                    Log.d(TAG, "VLM executed = $executed")

                    if (!executed) {
                        Log.e(TAG, "VLM action failed to execute")
                        break
                    }

                    if (parsed.intent == "type_and_send" && vlmAction.type == ActionType.TYPE) {
                        typeAndSendHasTyped = actionVerifier.textInEditable(collectScreenElements(), parsed.target)
                        if (!typeAndSendHasTyped) taskManager.failure()
                    }
                    if (parsed.intent == "type_and_send" && isSendDispatch(vlmAction)) {
                        sideEffectKey = "send:${currentForegroundPackage()}:${normalizeName(parsed.target)}"
                        sideEffectDispatched = true
                        taskManager.markSideEffect()
                    }
                    if (parsed.intent == "call" && vlmAction.type == ActionType.TAP && vlmAction.reason.contains("call", ignoreCase = true)) {
                        sideEffectKey = "call:${currentForegroundPackage()}:${normalizeName(parsed.target)}"
                        sideEffectDispatched = true
                        taskManager.markSideEffect()
                    }
                    taskManager.action(vlmAction.type.name, signature)
                    previousAction = vlmAction
                    previousSignature = signature
                    Thread.sleep(STEP_DELAY_MS)
                }

                else -> {
                    Log.e(TAG, "Unknown AnalyzeResult returned")
                    break
                }
            }
        }

        Log.e(TAG, "Vision loop exited after reaching max steps or failure")
        speakAndFinish("error", replyLanguage)
        return StepResult.Error
    }




    private fun decideWithAndroidOnly(parsed: ParsedCommand, elements: List<UiElement>): Action {
        val intent = parsed.intent
        val target = parsed.target.trim()

        return when (intent) {
            "back" -> Action(ActionType.BACK, reason = "direct back", confidence = 1.0)
            "home" -> Action(ActionType.HOME, reason = "direct home", confidence = 1.0)
            "scroll" -> Action(ActionType.SWIPE, direction = target, reason = "direct scroll", confidence = 1.0)

            "type" -> {
                val editable = elements.firstOrNull { it.editable }
                if (editable != null || isAnyInputFocused()) {
                    Action(ActionType.TYPE, element = editable, text = parsed.target, reason = "input ready", confidence = 0.95)
                } else Action(ActionType.NONE, reason = "no editable field found for type", confidence = 0.3)
            }

            "search" -> {
                val search = findSearchElement(elements)
                if (search != null) {
                    Action(
                        ActionType.TAP_THEN_TYPE,
                        element = search,
                        text = parsed.target,
                        reason = "search field found in current app",
                        confidence = 0.95
                    )
                } else Action(ActionType.NONE, reason = "no usable search field on current screen", confidence = 0.3)
            }

            "send" -> {
                val send = findSendButton(elements)
                if (send != null) Action(ActionType.TAP, element = send, reason = "send button found", confidence = 0.95)
                else Action(ActionType.NONE, reason = "send button not found", confidence = 0.3)
            }

            "type_and_send" -> {
                val editable = elements.firstOrNull { it.editable }
                val currentText = editable?.text?.toString()?.trim().orEmpty()
                val normalizedCurrent = normalizeName(currentText)
                val normalizedTarget = normalizeName(parsed.target)
                val inputHasMessage = currentText.isNotBlank() && normalizedCurrent == normalizedTarget

                // Step A: type exactly once when the message is not already present.
                if (!typeAndSendHasTyped && !inputHasMessage) {
                    if (editable != null || isAnyInputFocused()) {
                        Action(ActionType.TYPE, element = editable, text = parsed.target, reason = "message input ready; type once", confidence = 0.95)
                    } else {
                        Action(ActionType.NONE, reason = "no message input found", confidence = 0.3)
                    }
                } else {
                    // Step B: once the text is present, NEVER return TYPE again.
                    val send = findSendButton(elements)
                    if (send != null) {
                        val key = "send:${currentForegroundPackage()}:${normalizeName(parsed.target)}"
                        if (sideEffectKey == key && sideEffectDispatched) {
                            Action(ActionType.WAIT, reason = "send already dispatched; waiting for verification", confidence = 1.0)
                        } else {
                            Action(ActionType.TAP, element = send, reason = "send button found after typing", confidence = 0.95)
                        }
                    } else {
                        Action(ActionType.SEND, reason = "message typed; use IME send fallback", confidence = 0.9)
                    }
                }
            }

            "tap" -> {
                val matches = elements.filter {
                    target.isNotBlank() && nameMatches(it, target) && !isAvatarLike(it)
                }
                if (matches.size == 1) Action(ActionType.TAP, element = matches.first(), reason = "single target match", confidence = 0.9)
                else Action(ActionType.NONE, reason = "target '$target' unclear (${matches.size} matches)", confidence = 0.35)
            }

            "open_chat" -> {
                if (target.isBlank() && currentForegroundPackage() == resolveChatAppPackage()) {
                    Action(ActionType.DONE, reason = "messaging app already open", confidence = 1.0)
                } else if (currentScreenIsTargetChat(elements, target)) {
                    Action(ActionType.DONE, reason = "target chat already open", confidence = 1.0)
                } else {
                    val exact = elements.filter { nameMatches(it, target) && !isAvatarLike(it) }
                    when {
                        exact.size == 1 -> Action(ActionType.TAP, element = exact.first(), reason = "contact match on current screen", confidence = 0.9)
                        exact.size > 1 -> Action(ActionType.NONE, reason = "more than one possible contact match", confidence = 0.3)
                        else -> {
                            val search = findSearchElement(elements)
                            val searchIcon = elements.firstOrNull {
                                it.clickable && isSearchElement(it) && !isAppDrawerSearch(it)
                            }
                            when {
                                search != null -> Action(ActionType.TAP_THEN_TYPE, element = search, text = target, reason = "using current app search", confidence = 0.9)
                                searchIcon != null -> Action(ActionType.TAP, element = searchIcon, reason = "opening current app search", confidence = 0.85)
                                else -> Action(ActionType.NONE, reason = "contact not visible and current app has no search", confidence = 0.3)
                            }
                        }
                    }
                }
            }

            "call" -> {
                val callButton = findCallButton(elements)
                if (callButton != null) {
                    val key = "call:${currentForegroundPackage()}:${normalizeName(target)}"
                    if (sideEffectKey == key && sideEffectDispatched) {
                        Action(ActionType.WAIT, reason = "call already dispatched; waiting for verification", confidence = 1.0)
                    } else {
                        Action(ActionType.TAP, element = callButton, reason = "call control found in current app", confidence = 0.95)
                    }
                } else {
                    val matches = elements.filter { target.isNotBlank() && nameMatches(it, target) && !isAvatarLike(it) }
                    when {
                        matches.size == 1 -> Action(ActionType.TAP, element = matches.first(), reason = "contact found before call", confidence = 0.85)
                        matches.size > 1 -> Action(ActionType.NONE, reason = "more than one possible call target", confidence = 0.3)
                        else -> Action(ActionType.NONE, reason = "no callable control or target found", confidence = 0.3)
                    }
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
        val target = parsed.target.trim()

        return when (intent) {
            "open_chat" -> {
                currentScreenIsTargetChat(elements, target) ||
                        (target.isBlank() && currentForegroundPackage() == resolveChatAppPackage())
            }

            "call" -> {
                // A call action is successful only after the UI changes into an
                // active call/in-call state, not merely because a button was tapped.
                val activeCall = elements.any {
                    val t = (it.text ?: "").lowercase()
                    val d = (it.contentDescription ?: "").lowercase()
                    t.contains("end call") || t.contains("hang up") ||
                            d.contains("end call") || d.contains("hang up") ||
                            t.contains("mute") || d.contains("mute")
                }
                previousAction?.type == ActionType.TAP && screenChanged && activeCall
            }

            "type_and_send" -> {
                val editable = elements.firstOrNull { it.editable }
                val inputText = editable?.text?.toString()?.trim().orEmpty()
                val messageStillInInput = inputText.isNotBlank() &&
                        normalizeName(inputText) == normalizeName(target)
                val outgoingVisible = elements.any {
                    val t = it.text?.trim().orEmpty()
                    !it.editable &&
                            it.top > (resources.displayMetrics.heightPixels * 0.20f) &&
                            (t == target || (target.length > 3 && normalizeName(t) == normalizeName(target)))
                }
                val inputCleared = editable == null || inputText.isBlank()

                previousAction?.type in setOf(ActionType.TAP, ActionType.SEND) &&
                        inputCleared &&
                        !messageStillInInput &&
                        (screenChanged || outgoingVisible)
            }

            "type" -> {
                if (target.isBlank()) false
                else elements.any { it.editable && it.text?.toString() == parsed.target }
            }

            "search" -> {
                if (target.isBlank()) return false

                val nonEditableTargetVisible = elements.any {
                    val t = (it.text ?: "").trim()
                    val d = (it.contentDescription ?: "").lowercase()
                    !it.editable && t.length > 2 &&
                            (t.contains(parsed.target, ignoreCase = true) ||
                                    d.contains("search result") || d.contains("result"))
                }

                val searchFieldStillContainsTarget = elements.any {
                    it.editable && it.text?.toString()?.contains(parsed.target, ignoreCase = true) == true
                }

                // Typing alone is never success. A search succeeds only after
                // the submit action changes the UI and a non-input result state
                // is visible.
                nonEditableTargetVisible && previousAction != null && screenChanged &&
                        !searchFieldStillContainsTarget
            }

            "send" -> {
                val input = elements.firstOrNull { it.editable }
                val sendButtonGone = findSendButton(elements) == null
                previousAction?.type == ActionType.TAP &&
                        sideEffectDispatched &&
                        (input == null || input.text?.toString()?.trim().isNullOrBlank()) &&
                        sendButtonGone
            }
            "tap" -> previousAction?.type == ActionType.TAP && screenChanged
            "scroll" -> previousAction?.type == ActionType.SWIPE
            "back" -> previousAction?.type == ActionType.BACK
            "home" -> previousAction?.type == ActionType.HOME
            "open" -> {
                val resolved = findPackageByAppName(parsed.target)
                resolved != null && currentForegroundPackage() == resolved
            }
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
                if (typed) {
                    return if (pressEnter) performImeEnter() else true
                }

                val point = resolveTapPoint(action)
                if (point != null) {
                    val tapped = performTap(point.first, point.second)
                    if (tapped) {
                        Thread.sleep(500)
                        val retryTyped = performType(text)
                        return if (retryTyped && pressEnter) performImeEnter() else retryTyped
                    }
                }
                false
            }

            ActionType.SEND -> {
                val current = collectScreenElements()
                val input = current.firstOrNull { it.editable }
                val inputText = input?.text?.toString()?.trim().orEmpty()
                if (inputText.isBlank()) return false
                val send = findSendButton(current)
                if (send != null) {
                    val tapped = performTap(send.centerX(), send.centerY())
                    if (tapped) {
                        typeAndSendHasTyped = true
                        return true
                    }
                }
                performImeSend()
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
                val typed = performType(text)
                if (typed && pressEnter) performImeEnter()
                if (typed) Thread.sleep(900)   // ADD THIS - let results/state settle before next scan
                typed
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

    private fun isSendDispatch(action: Action): Boolean {
        if (action.type == ActionType.SEND) return true
        if (action.type != ActionType.TAP) return false
        val element = action.element ?: return action.reason.contains("send", ignoreCase = true)
        return isSendButton(element) || action.reason.contains("send", ignoreCase = true)
    }

    private fun isSendButton(element: UiElement): Boolean {
        val t = element.text.orEmpty().trim().lowercase()
        val d = element.contentDescription.orEmpty().trim().lowercase()
        val r = element.resourceId.orEmpty().trim().lowercase()
        return element.clickable && !isSensitiveElement(element) &&
                (t == "send" || d == "send" || d.contains("send") ||
                        t.contains("send") || r.contains("send")) &&
                !d.contains("money") && !d.contains("voice") && !r.contains("money")
    }

    private fun isSensitiveScreen(elements: List<UiElement>): Boolean {
        val visible = elements.asSequence()
            .flatMap { sequenceOf(it.text.orEmpty(), it.contentDescription.orEmpty(), it.resourceId.orEmpty()) }
            .joinToString(" ")
            .lowercase()
        return containsSensitiveText(visible)
    }

    private fun verifyNativeGoal(parsed: ParsedCommand, beforePackage: String?): Boolean {
        val elements = collectScreenElements()
        val afterPackage = currentForegroundPackage()
        return when (parsed.intent) {
            "open" -> findPackageByAppName(parsed.target)?.let { afterPackage == it } == true
            "screenshot" -> true
            "camera" -> afterPackage != null && afterPackage != beforePackage || elements.any {
                val s = (it.text.orEmpty() + " " + it.contentDescription.orEmpty()).lowercase()
                s.contains("take photo") || s.contains("take picture") || s.contains("shutter")
            }
            "timer", "calendar" -> afterPackage != null && afterPackage != beforePackage || elements.any {
                val s = (it.text.orEmpty() + " " + it.contentDescription.orEmpty()).lowercase()
                s.contains("timer") || s.contains("calendar") || s.contains("save event")
            }
            "call" -> elements.any {
                val s = (it.text.orEmpty() + " " + it.contentDescription.orEmpty()).lowercase()
                s.contains("end call") || s.contains("hang up") || s.contains("mute")
            }
            "type" -> elements.any { it.editable && it.text.orEmpty() == parsed.target }
            "home" -> afterPackage == null || elements.none { it.editable }
            "back" -> true
            else -> afterPackage != null && afterPackage != beforePackage || elements.any {
                val s = (it.text.orEmpty() + " " + it.contentDescription.orEmpty()).lowercase()
                s.contains("timer") || s.contains("calendar") || s.contains("take photo") ||
                        s.contains("camera") || s.contains("save event")
            }
        }
    }

    private fun isSensitiveElement(element: UiElement): Boolean {
        val combined = ((element.text ?: "") + " " + (element.contentDescription ?: "")).lowercase()
        return containsSensitiveText(combined)
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
            val elementId = if (obj.isNull("element_id")) null else obj.optInt("element_id")
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
                "send" -> ActionType.SEND
                "swipe" -> ActionType.SWIPE
                "wait" -> ActionType.WAIT
                "done" -> ActionType.DONE
                "ask_user" -> ActionType.ASK_USER
                else -> ActionType.NONE
            }

            // Confidence alone must not turn a concrete, resolvable action into
            // an immediate help request. The executor still enforces safety, and
            // tap actions must have a real element/grid/coordinate target below.
            // Keep the model's confidence for logging/diagnostics instead of
            // discarding an otherwise actionable result.

            var resolvedElement: UiElement? = null

            if (elementId != null) {
                resolvedElement = elements.firstOrNull { it.id == elementId }
            }

            if (resolvedElement == null && (type == ActionType.TAP || type == ActionType.TYPE)) {
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
                            val t = (el.text ?: "").lowercase()
                            val d = (el.contentDescription ?: "").lowercase()
                            if (el.clickable) score += 100
                            if (t == searchTerm || d == searchTerm) score += 100
                            if (t.contains(searchTerm) || d.contains(searchTerm)) score += 40
                            score += max(0, el.right - el.left)
                            score
                        }
                        else -> null
                    }
                }
            }

            // Only ask the user when the model gave us no usable element id,
            // no matching accessibility element, and no coordinate/grid fallback.
            var earlyAskUser: Action? = null
            if (type == ActionType.TAP && resolvedElement == null &&
                gridCell.isNullOrBlank() && (x == null || y == null)) {
                Log.d(TAG, "VLM tap: no usable element, grid, or coordinates")
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
            val resourceId = try { node.viewIdResourceName } catch (_: Exception) { null }
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
                        resourceId = resourceId,
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

        // Never offer dead elements (not clickable, not editable) to the
        // model - it can't interact with them, and it tends to fixate on
        // them anyway if they textually look relevant.
        val interactiveElements = elements.filter { it.clickable || it.editable }

        val candidateElements = if (intent == "open_chat" || intent == "call") {
            interactiveElements.filterNot {
                val d = (it.contentDescription ?: "").lowercase()
                d.contains("status") || d.contains("update") || d.contains("picture") || d.contains("photo") || d.contains("avatar")
            }
        } else {
            interactiveElements
        }

        val ranked = candidateElements.sortedByDescending { element ->
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
            obj.put("r", element.resourceId ?: "")
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
