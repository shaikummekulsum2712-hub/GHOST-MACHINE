package com.example.ghostmachine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

        /**
         * Capture a screenshot of the current screen and return it as a base64-encoded JPEG string.
         * This uses AccessibilityService.takeScreenshot() which requires API 30+ and the
         * android:canTakeScreenshot="true" flag in the accessibility service config.
         *
         * This is a BLOCKING call — it waits up to 5 seconds for the screenshot to complete.
         * Must be called from a background thread, NOT the main thread.
         *
         * @return Base64-encoded JPEG string, or null if capture failed
         */
        fun captureScreenBase64(): String? {
            val service = instance
            if (service == null) {
                Log.e("GhostService", "Accessibility service is not enabled — cannot capture screenshot")
                return null
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Log.e("GhostService", "takeScreenshot requires API 30+, current: ${Build.VERSION.SDK_INT}")
                return null
            }

            return service.doTakeScreenshot()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("GhostService", "Service connected — screenshot capture available")
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

    /**
     * Take a screenshot using the AccessibilityService API.
     * Uses a CountDownLatch to block until the async callback fires.
     * Must be called from a background thread.
     */
    private fun doTakeScreenshot(): String? {
        val latch = CountDownLatch(1)
        var resultBitmap: Bitmap? = null

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )
                            // Hardware bitmaps can't be compressed directly,
                            // so copy to a software bitmap
                            resultBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBitmap?.recycle()
                            screenshot.hardwareBuffer.close()
                        } catch (e: Exception) {
                            Log.e("GhostService", "Failed to process screenshot bitmap", e)
                        }
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e("GhostService", "Screenshot capture failed with error code: $errorCode")
                        latch.countDown()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("GhostService", "takeScreenshot() threw exception", e)
            return null
        }

        // Wait up to 5 seconds for the screenshot callback
        val completed = latch.await(5, TimeUnit.SECONDS)
        if (!completed) {
            Log.e("GhostService", "Screenshot capture timed out after 5 seconds")
            return null
        }

        val bitmap = resultBitmap ?: return null

        // Convert bitmap to base64 JPEG
        return try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            bitmap.recycle()
            val jpegBytes = outputStream.toByteArray()
            val base64String = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            Log.i("GhostService", "Screenshot captured: ${jpegBytes.size / 1024}KB JPEG, ${base64String.length / 1024}KB base64")
            base64String
        } catch (e: Exception) {
            Log.e("GhostService", "Failed to encode screenshot to base64", e)
            bitmap.recycle()
            null
        }
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
        // Try 1: Use focused input node
        var targetNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        // Try 2: Search the entire node tree for an editable text field
        if (targetNode == null) {
            Log.w("GhostService", "No focused input — searching node tree for editable field")
            targetNode = findEditableNode(rootInActiveWindow)
        }

        if (targetNode == null) {
            Log.e("GhostService", "No editable input field found anywhere on screen")
            return false
        }

        // Focus the node first
        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        val success = targetNode.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            arguments
        )
        targetNode.recycle()
        if (success) {
            Log.i("GhostService", "Typed text successfully: $text")
        } else {
            Log.e("GhostService", "Failed to perform SET_TEXT action")
        }
        return success
    }

    /**
     * Recursively search the accessibility node tree for an editable text field.
     */
    private fun findEditableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null

        if (root.isEditable) {
            return root
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findEditableNode(child)
            if (result != null) return result
            child.recycle()
        }

        return null
    }
}