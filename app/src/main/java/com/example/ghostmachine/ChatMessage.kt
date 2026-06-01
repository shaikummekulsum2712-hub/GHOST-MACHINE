package com.example.ghostmachine

import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val actionJson: String? = null,
    val status: MessageStatus = MessageStatus.SENT,
    val totalSteps: Int = 0,
    val currentStep: Int = 0
)

enum class MessageStatus {
    SENT,
    EXECUTING,
    SUCCESS,
    FAILED
}
