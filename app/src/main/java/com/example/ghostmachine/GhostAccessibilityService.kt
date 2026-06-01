package com.example.ghostmachine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

        fun executeAction(actionJson: String): Boolean {
            val service = instance
            if (service == null) {
                Log.e("GhostService", "Accessibility service is not enabled")
                return false
            }

            try {
                val jsonObject = org.json.JSONObject(actionJson)
                val action = jsonObject.optString("action")
                when (action) {
                    "tap" -> {
                        val x = jsonObject.optDouble("x", 0.0).toFloat()
                        val y = jsonObject.optDouble("y", 0.0).toFloat()
                        service.performTap(x, y)
                        return true
                    }
                    "swipe" -> {
                        val startX = jsonObject.optDouble("startX", 0.0).toFloat()
                        val startY = jsonObject.optDouble("startY", 0.0).toFloat()
                        val endX = jsonObject.optDouble("endX", 0.0).toFloat()
                        val endY = jsonObject.optDouble("endY", 0.0).toFloat()
                        val duration = jsonObject.optLong("duration", 300)
                        service.performSwipe(startX, startY, endX, endY, duration)
                        return true
                    }
                    "type" -> {
                        val text = jsonObject.optString("text", "")
                        return service.performType(text)
                    }
                }
            } catch (e: Exception) {
                Log.e("GhostService", "Failed to parse JSON action", e)
            }
            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("GhostService", "Service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i("GhostService", "Service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for fixed tap test
    }

    override fun onInterrupt() {
        Log.i("GhostService", "Service interrupted")
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        path.lineTo(x, y)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.i("GhostService", "Tap completed at $x, $y")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w("GhostService", "Tap cancelled")
                }
            },
            null
        )
    }

    private fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long) {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.i("GhostService", "Swipe completed from ($startX, $startY) to ($endX, $endY)")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w("GhostService", "Swipe cancelled")
                }
            },
            null
        )
    }

    private fun performType(text: String): Boolean {
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode == null) {
            Log.e("GhostService", "No focused input field found to type")
            return false
        }

        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        val success = focusedNode.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            arguments
        )
        focusedNode.recycle()
        if (success) {
            Log.i("GhostService", "Typed text successfully: $text")
        } else {
            Log.e("GhostService", "Failed to perform SET_TEXT action")
        }
        return success
    }
}