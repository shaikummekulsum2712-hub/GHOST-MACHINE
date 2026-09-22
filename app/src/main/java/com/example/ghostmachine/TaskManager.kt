package com.example.ghostmachine

import android.util.Log

class TaskManager {
    companion object {
        private const val TAG = "GhostTaskManager"
        private const val MAX_FAILURES = 3
        private const val MAX_HISTORY = 8
    }

    private var state: TaskState? = null
    private val history = ArrayDeque<String>()

    @Synchronized
    fun begin(goal: String, intent: String, target: String) {
        state = TaskState(goal = goal, intent = intent, target = target)
        history.clear()
        Log.d(TAG, "Task started: intent=$intent target=$target")
    }

    @Synchronized
    fun observe(
        packageName: String?,
        signature: String?,
        app: String? = null,
        screenType: String = "unknown"
    ) {
        state = state?.copy(
            currentPackage = packageName,
            currentApp = app,
            lastScreenSignature = signature,
            screenType = screenType
        )
    }

    @Synchronized
    fun action(name: String, screenSignature: String? = null) {
        state = state?.copy(
            currentStep = (state?.currentStep ?: 0) + 1,
            lastAction = name
        )
        if (!screenSignature.isNullOrBlank()) {
            history.add("$screenSignature|$name")
            while (history.size > MAX_HISTORY) history.removeFirst()
        }
    }

    @Synchronized
    fun success() {
        state = state?.copy(
            completedSteps = (state?.completedSteps ?: 0) + 1,
            failedAttempts = 0
        )
    }

    @Synchronized
    fun failure() {
        state = state?.copy(
            failedAttempts = (state?.failedAttempts ?: 0) + 1
        )
    }

    @Synchronized
    fun markSideEffect() {
        state = state?.copy(sideEffectDispatched = true)
    }

    @Synchronized
    fun loopDetected(): Boolean {
        if (history.size < 4) return false
        val recent = history.takeLast(4)
        return recent[0] == recent[2] && recent[1] == recent[3]
    }

    @Synchronized
    fun shouldStop(): Boolean {
        val current = state ?: return true
        return current.cancelled || current.failedAttempts >= MAX_FAILURES || loopDetected()
    }

    @Synchronized
    fun cancel() {
        state = state?.copy(cancelled = true)
    }

    @Synchronized
    fun finish() {
        state = null
        history.clear()
    }

    @Synchronized
    fun snapshot(): TaskState? = state
}