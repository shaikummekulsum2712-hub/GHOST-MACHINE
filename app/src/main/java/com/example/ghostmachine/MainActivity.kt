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
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val messages = mutableStateListOf<ChatMessage>()
    private var isTyping by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            GHOSTMACHINETheme {
                ChatScreen(
                    messages = messages,
                    isTyping = isTyping,
                    onSendMessage = { command ->
                        handleUserCommand(command)
                    },
                    onOpenAccessibility = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        startActivity(intent)
                    }
                )
            }
        }
    }

    private fun handleUserCommand(command: String) {
        val userMessage = ChatMessage(
            text = command,
            isUser = true
        )

        messages.add(userMessage)
        isTyping = true

        Thread {
            val serviceEnabled = GhostAccessibilityService.instance != null

            if (!serviceEnabled) {
                Handler(Looper.getMainLooper()).post {
                    isTyping = false
                    messages.add(
                        ChatMessage(
                            text = "Accessibility service is not enabled. Please enable Ghost Machine in Accessibility settings.",
                            isUser = false,
                            status = MessageStatus.FAILED
                        )
                    )

                    Toast.makeText(
                        this,
                        "Enable Accessibility Service first",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@Thread
            }

            val screenshotBytes = GhostAccessibilityService.captureScreenJpegBytes()

            if (screenshotBytes == null) {
                Handler(Looper.getMainLooper()).post {
                    isTyping = false
                    messages.add(
                        ChatMessage(
                            text = "Failed to capture screenshot. Make sure Accessibility permission is enabled and phone is Android 11+.",
                            isUser = false,
                            status = MessageStatus.FAILED
                        )
                    )
                }
                return@Thread
            }

            val responseJson = ApiClient.analyzeScreen(command, screenshotBytes)

            Handler(Looper.getMainLooper()).post {
                isTyping = false

                if (responseJson == null) {
                    messages.add(
                        ChatMessage(
                            text = "Backend connection failed. Check backend server and ADB reverse.",
                            isUser = false,
                            status = MessageStatus.FAILED
                        )
                    )
                    return@post
                }

                try {
                    val actionObject = JSONObject(responseJson)
                    val action = actionObject.optString("action", "unknown")
                    val reason = actionObject.optString("reason", "No reason given")
                    val confidence = actionObject.optDouble("confidence", 0.0)

                    val aiMessageId = UUID.randomUUID().toString()

                    val aiMessage = ChatMessage(
                        id = aiMessageId,
                        text = buildAiMessageText(action, reason, confidence),
                        isUser = false,
                        actionJson = responseJson,
                        status = MessageStatus.EXECUTING
                    )

                    messages.add(aiMessage)

                    if (action == "ask_user") {
                        updateMessageStatus(aiMessageId, MessageStatus.FAILED)
                        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
                        return@post
                    }

                    if (action == "done") {
                        updateMessageStatus(aiMessageId, MessageStatus.SUCCESS)
                        return@post
                    }

                    Handler(Looper.getMainLooper()).postDelayed({
                        Thread {
                            val success = GhostAccessibilityService.executeAction(responseJson)

                            Handler(Looper.getMainLooper()).post {
                                updateMessageStatus(
                                    aiMessageId,
                                    if (success) MessageStatus.SUCCESS else MessageStatus.FAILED
                                )

                                if (!success) {
                                    Toast.makeText(
                                        this,
                                        "Action failed: $action",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }.start()
                    }, 1200)

                } catch (e: Exception) {
                    messages.add(
                        ChatMessage(
                            text = "Invalid backend response: ${e.message}",
                            isUser = false,
                            actionJson = responseJson,
                            status = MessageStatus.FAILED
                        )
                    )
                }
            }
        }.start()
    }

    private fun buildAiMessageText(
        action: String,
        reason: String,
        confidence: Double
    ): String {
        return when (action) {
            "tap" -> "I will tap the target.\nReason: $reason\nConfidence: $confidence"
            "swipe" -> "I will swipe on the screen.\nReason: $reason\nConfidence: $confidence"
            "type" -> "I will type the required text.\nReason: $reason\nConfidence: $confidence"
            "wait" -> "I will wait for the screen to load.\nReason: $reason\nConfidence: $confidence"
            "done" -> "Task already done.\nReason: $reason\nConfidence: $confidence"
            "ask_user" -> "Need your confirmation.\nReason: $reason"
            else -> "Unknown action: $action\nReason: $reason"
        }
    }

    private fun updateMessageStatus(messageId: String, status: MessageStatus) {
        val index = messages.indexOfFirst { it.id == messageId }

        if (index != -1) {
            messages[index] = messages[index].copy(status = status)
        }
    }
}