package com.example.ghostmachine

import android.util.Log

class TaskManager {
    companion object {
        private const val TAG = "GhostTaskManager"
        private const val MAX_FAILURES = 3
        private const val MAX_HISTORY = 8
    }

    @Volatile private var state: TaskState? = null
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
        state?.apply {
            currentPackage = packageName
            currentApp = app
            lastScreenSignature = signature
            this.screenType = screenType
        }
    }

    @Synchronized
    fun action(name: String, screenSignature: String? = null) {
        state?.apply {
            currentStep++
            lastAction = name
        }
        if (!screenSignature.isNullOrBlank()) {
            history.add("$screenSignature|$name")
            while (history.size > MAX_HISTORY) history.removeFirst()
        }
    }

    @Synchronized
    fun success() {
        state?.apply {
            completedSteps++
            failedAttempts = 0
        }
    }

    @Synchronized
    fun failure() {
        state?.failedAttempts = (state?.failedAttempts ?: 0) + 1
    }

    @Synchronized
    fun markSideEffect() {
        state?.sideEffectDispatched = true
    }

    @Synchronized
    fun loopDetected(): Boolean {
        if (history.size < 4) return false
        val recent = history.takeLast(4)
        return recent[0] == recent[2] && recent[1] == recent[3]
    }

    @Synchronized
    fun shouldStop(): Boolean =
        state?.cancelled == true ||
                (state?.failedAttempts ?: 0) >= MAX_FAILURES ||
                loopDetected()

    @Synchronized
    fun cancel() {
        state?.cancelled = true
    }

    @Synchronized
    fun finish() {
        state = null
        history.clear()
    }

    @Synchronized
    fun snapshot(): TaskState? = state?.copy()
}
