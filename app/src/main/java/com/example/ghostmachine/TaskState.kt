package com.example.ghostmachine

data class TaskState(
    val goal: String = "",
    var intent: String = "",
    var target: String = "",
    var currentStep: Int = 0,
    var completedSteps: Int = 0,
    var failedAttempts: Int = 0,
    var lastAction: String? = null,
    var lastScreenSignature: String? = null,
    var currentPackage: String? = null,
    var currentApp: String? = null,
    var screenType: String = "unknown",
    var sideEffectDispatched: Boolean = false,
    var cancelled: Boolean = false
)
