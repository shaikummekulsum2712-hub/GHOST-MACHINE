package com.example.ghostmachine

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.example.ghostmachine.ui.theme.GHOSTMACHINETheme
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val messages = mutableStateListOf<ChatMessage>()
    private var isTyping by mutableStateOf(false)
    private var isPollingActive = true
    private var visionLoopActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start background polling for PC Browser commands
        startCommandPolling()

        setContent {
            GHOSTMACHINETheme {
                ChatScreen(
                    messages = messages,
                    isTyping = isTyping,
                    onSendMessage = { command ->
                        // Add user message
                        val userMsg = ChatMessage(
                            text = command,
                            isUser = true
                        )
                        messages.add(userMsg)
                        isTyping = true

                        // Call backend on background thread — use vision loop
                        Thread {
                            // Check if accessibility service is available for screenshot
                            val hasAccessibility = GhostAccessibilityService.instance != null

                            if (hasAccessibility) {
                                // ========== VISION LOOP MODE ==========
                                // Start a vision loop — AI sees screen step-by-step
                                android.util.Log.i("GhostMain", "👁️ Starting vision loop for: $command")
                                val startRes = ApiClient.startVisionLoop(command)

                                if (startRes != null) {
                                    try {
                                        val startObj = JSONObject(startRes)
                                        val loopId = startObj.getString("loop_id")
                                        val msgId = loopId

                                        Handler(Looper.getMainLooper()).post {
                                            isTyping = false
                                            val aiMsg = ChatMessage(
                                                id = msgId,
                                                text = "👁️ Vision Loop active — AI is watching the screen...",
                                                isUser = false,
                                                actionJson = "Vision Loop: analyzing screen step by step",
                                                status = MessageStatus.EXECUTING,
                                                totalSteps = 0,
                                                currentStep = 0
                                            )
                                            messages.add(aiMsg)

                                            Toast.makeText(
                                                this@MainActivity,
                                                "👁️ Vision Loop started — switch to target app!",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }

                                        // Give user time to switch apps
                                        Thread.sleep(3000)

                                        // Execute the vision loop
                                        executeVisionLoop(command, loopId, msgId)
                                    } catch (e: Exception) {
                                        android.util.Log.e("GhostMain", "Vision loop start failed", e)
                                        Handler(Looper.getMainLooper()).post {
                                            isTyping = false
                                            messages.add(ChatMessage(
                                                text = "Failed to start vision loop: ${e.message}",
                                                isUser = false,
                                                status = MessageStatus.FAILED
                                            ))
                                        }
                                    }
                                } else {
                                    // Fallback: vision loop start failed, try legacy mode
                                    android.util.Log.w("GhostMain", "Vision loop start failed, falling back to batch mode")
                                    executeLegacyBatchMode(command)
                                }
                            } else {
                                // No accessibility service — use legacy batch mode
                                android.util.Log.w("GhostMain", "No accessibility service — using batch mode")
                                executeLegacyBatchMode(command)
                            }
                        }.start()
                    },
                    onOpenAccessibility = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        startActivity(intent)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isPollingActive = false
    }

    /**
     * Periodically check for commands queued by the PC Companion browser interface.
     * When a web command arrives with vision_loop=true, the phone enters vision loop mode.
     */
    private fun startCommandPolling() {
        Thread {
            while (isPollingActive) {
                try {
                    Thread.sleep(2000) // Poll every 2 seconds
                    val pollRes = ApiClient.pollCommand()
                    if (pollRes != null) {
                        val pollObj = JSONObject(pollRes)
                        if (pollObj.optBoolean("has_command", false)) {
                            val msgId = pollObj.getString("id")
                            val command = pollObj.getString("command")
                            val summary = pollObj.getString("summary")
                            val isVisionLoop = pollObj.optBoolean("vision_loop", false)

                            if (isVisionLoop) {
                                // 👁️ Web-initiated vision loop
                                android.util.Log.i("GhostMain", "👁️ Web-initiated vision loop: $command")

                                Handler(Looper.getMainLooper()).post {
                                    val userMsg = ChatMessage(text = command, isUser = true)
                                    messages.add(userMsg)

                                    val aiMsg = ChatMessage(
                                        id = msgId,
                                        text = "👁️ Vision Loop active — AI is watching the screen...",
                                        isUser = false,
                                        actionJson = "Vision Loop: analyzing screen step by step",
                                        status = MessageStatus.EXECUTING,
                                        totalSteps = 0,
                                        currentStep = 0
                                    )
                                    messages.add(aiMsg)

                                    Toast.makeText(
                                        this,
                                        "👁️ Vision Loop from PC — executing...",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }

                                // Small delay then start vision loop
                                Thread.sleep(1500)
                                executeVisionLoop(command, msgId, msgId)
                                continue
                            }

                            // Legacy: handle non-vision-loop commands (old batch mode)
                            val stepsArray = pollObj.getJSONArray("steps")
                            val needsScreenshot = pollObj.optBoolean("needs_screenshot", false)

                            if (needsScreenshot) {
                                // 📸 Web command: capture screenshot and re-plan with vision
                                android.util.Log.i("GhostMain", "Web command needs screenshot — capturing...")
                                val screenshot = GhostAccessibilityService.captureScreenBase64()

                                if (screenshot != null) {
                                    android.util.Log.i("GhostMain", "Uploading screenshot for vision re-plan...")
                                    val replanRes = ApiClient.uploadScreenshot(screenshot, msgId)

                                    if (replanRes != null) {
                                        val replanObj = JSONObject(replanRes)
                                        val status = replanObj.optString("status", "")

                                        if (status == "replanned") {
                                            val newSummary = replanObj.optString("summary", summary)
                                            val newStepsArray = replanObj.optJSONArray("steps") ?: stepsArray

                                            Handler(Looper.getMainLooper()).post {
                                                val userMsg = ChatMessage(text = command, isUser = true)
                                                messages.add(userMsg)

                                                val aiMsg = ChatMessage(
                                                    id = msgId,
                                                    text = "👁️ $newSummary",
                                                    isUser = false,
                                                    actionJson = formatStepsForDisplay(newStepsArray),
                                                    status = MessageStatus.EXECUTING,
                                                    totalSteps = newStepsArray.length(),
                                                    currentStep = 0
                                                )
                                                messages.add(aiMsg)

                                                executeStepsSequentially(
                                                    stepsArray = newStepsArray,
                                                    aiMsgId = msgId,
                                                    stepIndex = 0
                                                )
                                            }
                                            continue
                                        }
                                    }
                                }
                                android.util.Log.w("GhostMain", "Vision re-plan failed — using blind mode steps")
                            }

                            // Execute with original steps (blind mode fallback)
                            Handler(Looper.getMainLooper()).post {
                                val userMsg = ChatMessage(text = command, isUser = true)
                                messages.add(userMsg)

                                val aiMsg = ChatMessage(
                                    id = msgId,
                                    text = "🙈 $summary",
                                    isUser = false,
                                    actionJson = formatStepsForDisplay(stepsArray),
                                    status = MessageStatus.EXECUTING,
                                    totalSteps = stepsArray.length(),
                                    currentStep = 0
                                )
                                messages.add(aiMsg)

                                Toast.makeText(
                                    this,
                                    "Executing remote command: $command",
                                    Toast.LENGTH_LONG
                                ).show()

                                executeStepsSequentially(
                                    stepsArray = stepsArray,
                                    aiMsgId = msgId,
                                    stepIndex = 0
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Quiet fail
                }
            }
        }.start()
    }

    /**
     * Execute an array of action steps one-by-one with delays between them.
     */
    private fun executeStepsSequentially(
        stepsArray: JSONArray,
        aiMsgId: String,
        stepIndex: Int
    ) {
        if (stepIndex >= stepsArray.length()) {
            val idx = messages.indexOfFirst { it.id == aiMsgId }
            if (idx != -1) {
                messages[idx] = messages[idx].copy(
                    status = MessageStatus.SUCCESS,
                    currentStep = stepsArray.length()
                )
            }
            Toast.makeText(this, "All ${stepsArray.length()} steps completed!", Toast.LENGTH_SHORT).show()
            Thread {
                ApiClient.reportStatus(
                    id = aiMsgId,
                    status = "SUCCESS",
                    currentStep = stepsArray.length(),
                    totalSteps = stepsArray.length()
                )
            }.start()
            return
        }

        val step = stepsArray.getJSONObject(stepIndex)
        val action = step.optString("action", "")
        val reason = step.optString("reason", "")
        val stepNum = stepIndex + 1

        val idx = messages.indexOfFirst { it.id == aiMsgId }
        if (idx != -1) {
            messages[idx] = messages[idx].copy(currentStep = stepNum)
        }

        val preDelay = if (stepIndex == 0) 3000L else 800L
        Handler(Looper.getMainLooper()).postDelayed({
            Thread {
                ApiClient.reportStatus(
                    id = aiMsgId,
                    status = "EXECUTING",
                    currentStep = stepNum,
                    totalSteps = stepsArray.length(),
                    currentAction = action,
                    currentReason = reason
                )
            }.start()

            when (action) {
                "wait" -> {
                    val waitDuration = step.optLong("duration", 1000)
                    Handler(Looper.getMainLooper()).postDelayed({
                        executeStepsSequentially(stepsArray, aiMsgId, stepIndex + 1)
                    }, waitDuration)
                }
                "tap", "swipe", "type" -> {
                    val stepJson = step.toString()
                    val success = GhostAccessibilityService.executeAction(stepJson)

                    if (success) {
                        executeStepsSequentially(stepsArray, aiMsgId, stepIndex + 1)
                    } else {
                        val failIdx = messages.indexOfFirst { it.id == aiMsgId }
                        if (failIdx != -1) {
                            messages[failIdx] = messages[failIdx].copy(
                                status = MessageStatus.FAILED,
                                currentStep = stepNum
                            )
                        }
                        Toast.makeText(
                            this,
                            "Failed at step $stepNum. Enable Accessibility Service first.",
                            Toast.LENGTH_LONG
                        ).show()
                        Thread {
                            ApiClient.reportStatus(
                                id = aiMsgId,
                                status = "FAILED",
                                currentStep = stepNum,
                                totalSteps = stepsArray.length(),
                                currentAction = action,
                                currentReason = reason,
                                error = "Failed to execute accessibility action: $action"
                            )
                        }.start()
                    }
                }
                else -> {
                    executeStepsSequentially(stepsArray, aiMsgId, stepIndex + 1)
                }
            }
        }, preDelay)
    }

    private fun formatStepsForDisplay(stepsArray: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until stepsArray.length()) {
            val step = stepsArray.getJSONObject(i)
            val action = step.optString("action", "?")
            val reason = step.optString("reason", "")
            sb.append("${i + 1}. [$action] $reason\n")
        }
        return sb.toString().trimEnd()
    }

    private fun buildSingleActionDescription(json: String): String {
        return try {
            val obj = JSONObject(json)
            val action = obj.optString("action", "unknown")
            val reason = obj.optString("reason", "")
            when (action) {
                "tap" -> {
                    val x = obj.optInt("x", 0)
                    val y = obj.optInt("y", 0)
                    "I'll tap at ($x, $y).${if (reason.isNotEmpty()) "\n💡 $reason" else ""}"
                }
                "swipe" -> "I'll perform a swipe gesture.${if (reason.isNotEmpty()) "\n💡 $reason" else ""}"
                "type" -> {
                    val text = obj.optString("text", "")
                    "I'll type: \"$text\"${if (reason.isNotEmpty()) "\n💡 $reason" else ""}"
                }
                else -> "Performing action: $action"
            }
        } catch (e: Exception) {
            "Executing action..."
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  VISION LOOP EXECUTOR — Closed-loop step-by-step reactive execution
    // ════════════════════════════════════════════════════════════════════

    private fun executeVisionLoop(goal: String, loopId: String, msgId: String) {
        visionLoopActive = true
        var stepIndex = 0

        android.util.Log.i("GhostMain", "👁️ Vision loop executor started for goal: $goal")

        try {
            while (visionLoopActive && stepIndex < 25) {
                android.util.Log.i("GhostMain", "📸 Vision step ${stepIndex + 1}: Capturing screenshot...")
                val screenshot = GhostAccessibilityService.captureScreenBase64()

                if (screenshot == null) {
                    android.util.Log.e("GhostMain", "Screenshot capture failed — aborting vision loop")
                    Handler(Looper.getMainLooper()).post {
                        val idx = messages.indexOfFirst { it.id == msgId }
                        if (idx != -1) {
                            messages[idx] = messages[idx].copy(
                                text = "❌ Screenshot capture failed — vision loop aborted",
                                status = MessageStatus.FAILED
                            )
                        }
                    }
                    break
                }

                Handler(Looper.getMainLooper()).post {
                    val idx = messages.indexOfFirst { it.id == msgId }
                    if (idx != -1) {
                        messages[idx] = messages[idx].copy(
                            text = "👁️ Vision Loop — Step ${stepIndex + 1}: AI analyzing screen...",
                            currentStep = stepIndex + 1
                        )
                    }
                }

                android.util.Log.i("GhostMain", "🧠 Sending screenshot to AI for analysis...")
                val visionRes = ApiClient.sendScreenshotForVision(screenshot, loopId)

                if (visionRes == null) {
                    android.util.Log.e("GhostMain", "Vision API call failed")
                    Handler(Looper.getMainLooper()).post {
                        val idx = messages.indexOfFirst { it.id == msgId }
                        if (idx != -1) {
                            messages[idx] = messages[idx].copy(
                                text = "❌ AI vision call failed — check backend connection",
                                status = MessageStatus.FAILED
                            )
                        }
                    }
                    break
                }

                val responseObj = JSONObject(visionRes)

                if (responseObj.has("error") && !responseObj.isNull("error")) {
                    val error = responseObj.getString("error")
                    val shouldRetry = responseObj.optBoolean("retry", false)

                    if (shouldRetry) {
                        android.util.Log.w("GhostMain", "AI response malformed, retrying... ($error)")
                        Thread.sleep(1000)
                        continue
                    }

                    android.util.Log.e("GhostMain", "Vision loop error: $error")
                    Handler(Looper.getMainLooper()).post {
                        val idx = messages.indexOfFirst { it.id == msgId }
                        if (idx != -1) {
                            messages[idx] = messages[idx].copy(
                                text = "❌ Vision loop error: $error",
                                status = MessageStatus.FAILED
                            )
                        }
                    }
                    break
                }

                val actionObj = responseObj.optJSONObject("action")
                if (actionObj == null) {
                    android.util.Log.e("GhostMain", "No action in vision response")
                    break
                }

                val actionType = actionObj.optString("action", "unknown")
                val actionReason = actionObj.optString("reason", "")

                android.util.Log.i("GhostMain", "🎯 Step ${stepIndex + 1}: [$actionType] $actionReason")

                if (actionType == "done") {
                    android.util.Log.i("GhostMain", "✅ GOAL ACHIEVED: $actionReason")
                    Handler(Looper.getMainLooper()).post {
                        val idx = messages.indexOfFirst { it.id == msgId }
                        if (idx != -1) {
                            messages[idx] = messages[idx].copy(
                                text = "✅ $actionReason",
                                status = MessageStatus.SUCCESS,
                                currentStep = stepIndex + 1,
                                totalSteps = stepIndex + 1
                            )
                        }
                        Toast.makeText(
                            this@MainActivity,
                            "✅ Vision loop complete: $actionReason",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    break
                }

                Handler(Looper.getMainLooper()).post {
                    val idx = messages.indexOfFirst { it.id == msgId }
                    if (idx != -1) {
                        messages[idx] = messages[idx].copy(
                            text = "👁️ Step ${stepIndex + 1}: [$actionType] $actionReason",
                            currentStep = stepIndex + 1,
                            totalSteps = stepIndex + 1
                        )
                    }
                }

                if (actionType == "wait") {
                    val waitDuration = actionObj.optLong("duration", 1000)
                    android.util.Log.i("GhostMain", "Waiting ${waitDuration}ms...")
                    Thread.sleep(waitDuration)
                } else {
                    val actionJson = actionObj.toString()
                    val success = GhostAccessibilityService.executeAction(actionJson)
                    ApiClient.reportVisionActionComplete(loopId, stepIndex + 1, success)

                    if (!success) {
                        android.util.Log.w("GhostMain", "Action execution failed, but continuing loop")
                    }
                }

                val settleDelay = when (actionType) {
                    "tap" -> 1500L
                    "swipe" -> 1200L
                    "type" -> 800L
                    "wait" -> 200L
                    else -> 1000L
                }
                Thread.sleep(settleDelay)

                stepIndex++
            }
        } catch (e: Exception) {
            android.util.Log.e("GhostMain", "Vision loop exception", e)
            Handler(Looper.getMainLooper()).post {
                val idx = messages.indexOfFirst { it.id == msgId }
                if (idx != -1) {
                    messages[idx] = messages[idx].copy(
                        text = "❌ Vision loop error: ${e.message}",
                        status = MessageStatus.FAILED
                    )
                }
            }
        } finally {
            visionLoopActive = false
            android.util.Log.i("GhostMain", "👁️ Vision loop ended after $stepIndex steps")
        }
    }

    private fun executeLegacyBatchMode(command: String) {
        val screenshot = GhostAccessibilityService.captureScreenBase64()
        val responseJson = ApiClient.getNextAction(command, screenshot)

        Handler(Looper.getMainLooper()).post {
            isTyping = false

            if (responseJson != null) {
                try {
                    val responseObj = JSONObject(responseJson)
                    val summary = responseObj.optString("summary", "Executing actions...")
                    val stepsArray = responseObj.optJSONArray("steps") ?: JSONArray()
                    val msgId = responseObj.optString("id", UUID.randomUUID().toString())

                    if (stepsArray.length() == 0) {
                        messages.add(ChatMessage(
                            id = msgId,
                            text = summary,
                            isUser = false,
                            actionJson = responseJson,
                            status = MessageStatus.SENT
                        ))
                        return@post
                    }

                    val visionTag = if (screenshot != null) "👁️ " else "🙈 "
                    val aiMsg = ChatMessage(
                        id = msgId,
                        text = visionTag + summary,
                        isUser = false,
                        actionJson = formatStepsForDisplay(stepsArray),
                        status = MessageStatus.EXECUTING,
                        totalSteps = stepsArray.length(),
                        currentStep = 0
                    )
                    messages.add(aiMsg)

                    Toast.makeText(
                        this@MainActivity,
                        "Executing ${stepsArray.length()} steps — switch apps now!",
                        Toast.LENGTH_LONG
                    ).show()

                    executeStepsSequentially(
                        stepsArray = stepsArray,
                        aiMsgId = msgId,
                        stepIndex = 0
                    )
                } catch (e: Exception) {
                    val fallbackId = UUID.randomUUID().toString()
                    messages.add(ChatMessage(
                        id = fallbackId,
                        text = buildSingleActionDescription(responseJson),
                        isUser = false,
                        actionJson = responseJson,
                        status = MessageStatus.EXECUTING
                    ))

                    Handler(Looper.getMainLooper()).postDelayed({
                        val success = GhostAccessibilityService.executeAction(responseJson)
                        val idx = messages.indexOfFirst { it.id == fallbackId }
                        if (idx != -1) {
                            messages[idx] = messages[idx].copy(
                                status = if (success) MessageStatus.SUCCESS else MessageStatus.FAILED
                            )
                        }
                    }, 3000)
                }
            } else {
                messages.add(ChatMessage(
                    text = "Failed to connect to the backend server. Make sure it's running on your PC and ADB reverse is set up.",
                    isUser = false,
                    status = MessageStatus.FAILED
                ))
                Toast.makeText(
                    this@MainActivity,
                    "Backend connection failed",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}