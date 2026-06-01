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

                        // Call backend on background thread
                        Thread {
                            val responseJson = ApiClient.getNextAction(command)

                            Handler(Looper.getMainLooper()).post {
                                isTyping = false

                                if (responseJson != null) {
                                    try {
                                        val responseObj = JSONObject(responseJson)
                                        val summary = responseObj.optString("summary", "Executing actions...")
                                        val stepsArray = responseObj.optJSONArray("steps") ?: JSONArray()
                                        val msgId = responseObj.optString("id", UUID.randomUUID().toString())

                                        if (stepsArray.length() == 0) {
                                            // No steps — just show the summary (e.g., AI couldn't do it)
                                            val aiMsg = ChatMessage(
                                                id = msgId,
                                                text = summary,
                                                isUser = false,
                                                actionJson = responseJson,
                                                status = MessageStatus.SENT
                                            )
                                            messages.add(aiMsg)
                                            return@post
                                        }

                                        // Create AI message with step tracking
                                        val aiMsg = ChatMessage(
                                            id = msgId,
                                            text = summary,
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

                                        // Execute steps sequentially with delays
                                        executeStepsSequentially(
                                            stepsArray = stepsArray,
                                            aiMsgId = msgId,
                                            stepIndex = 0
                                        )

                                    } catch (e: Exception) {
                                        // Fallback: treat as old single-action format
                                        val fallbackId = UUID.randomUUID().toString()
                                        val aiMsg = ChatMessage(
                                            id = fallbackId,
                                            text = buildSingleActionDescription(responseJson),
                                            isUser = false,
                                            actionJson = responseJson,
                                            status = MessageStatus.EXECUTING
                                        )
                                        messages.add(aiMsg)

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
                                    val errorMsg = ChatMessage(
                                        text = "Failed to connect to the backend server. Make sure it's running on your PC and ADB reverse is set up.",
                                        isUser = false,
                                        status = MessageStatus.FAILED
                                    )
                                    messages.add(errorMsg)

                                    Toast.makeText(
                                        this@MainActivity,
                                        "Backend connection failed",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
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
                            val stepsArray = pollObj.getJSONArray("steps")

                            Handler(Looper.getMainLooper()).post {
                                // Add user command
                                val userMsg = ChatMessage(
                                    text = command,
                                    isUser = true
                                )
                                messages.add(userMsg)

                                // Add AI response bubble
                                val aiMsg = ChatMessage(
                                    id = msgId,
                                    text = summary,
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

                                // Execute steps
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
            // All steps completed!
            val idx = messages.indexOfFirst { it.id == aiMsgId }
            if (idx != -1) {
                messages[idx] = messages[idx].copy(
                    status = MessageStatus.SUCCESS,
                    currentStep = stepsArray.length()
                )
            }
            Toast.makeText(this, "All ${stepsArray.length()} steps completed!", Toast.LENGTH_SHORT).show()

            // Report final success status back to backend on background thread
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

        // Update local progress in the UI
        val idx = messages.indexOfFirst { it.id == aiMsgId }
        if (idx != -1) {
            messages[idx] = messages[idx].copy(currentStep = stepNum)
        }

        // Determine delay before executing this step
        val preDelay = if (stepIndex == 0) 3000L else 800L  // First step: 3s to switch apps, rest: 800ms for UI to settle

        Handler(Looper.getMainLooper()).postDelayed({
            // Report current executing step back to backend
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
                        // Mark locally as failed
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

                        // Report failure back to backend
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
                    // Unknown action, skip
                    executeStepsSequentially(stepsArray, aiMsgId, stepIndex + 1)
                }
            }
        }, preDelay)
    }

    /**
     * Format steps array for display in the expandable JSON section.
     */
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

    /**
     * Fallback for old single-action response format.
     */
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
}