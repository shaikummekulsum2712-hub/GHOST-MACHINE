package com.example.ghostmachine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class GhostAccessibilityService : AccessibilityService() {

    companion object {
        var instance: GhostAccessibilityService? = null

        fun tap(x: Float, y: Float): Boolean {
            val service = instance
            if (service == null) {
                Log.e("GhostService", "Accessibility service is not enabled")
                return false
            }

            service.performTap(x, y)
            return true
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("GhostService", "Service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d("GhostService", "Service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for fixed tap test
    }

    override fun onInterrupt() {
        Log.d("GhostService", "Service interrupted")
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d("GhostService", "Tap completed at $x, $y")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.d("GhostService", "Tap cancelled")
                }
            },
            null
        )
    }
}