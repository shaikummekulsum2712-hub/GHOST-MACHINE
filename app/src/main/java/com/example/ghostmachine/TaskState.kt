package com.example.ghostmachine

/**
 * Immutable representation of the current execution state of an agent task.
 */
data class TaskState(
    val goal: String = "",
    val intent: String = "",
    val target: String = "",
    val currentStep: Int = 0,
    val completedSteps: Int = 0,
    val failedAttempts: Int = 0,
    val lastAction: String? = null,
    val lastScreenSignature: String? = null,
    val currentPackage: String? = null,
    val currentApp: String? = null,
    val screenType: String = "unknown",
    val sideEffectDispatched: Boolean = false,
    val cancelled: Boolean = false
)