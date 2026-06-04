package com.example.ghostmachine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GhostAccessibilityService : AccessibilityService() {

    companion object {
        var instance: GhostAccessibilityService? = null

        fun captureScreenJpegBytes(): ByteArray? {
            val service = instance

            if (service == null) {
                Log.e("GhostService", "Accessibility service is not enabled")
                return null
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Log.e("GhostService", "Screenshot needs Android 11+")
                return null
            }

            return service.takeScreenshotAsJpegBytes()
        }

        fun executeAction(actionJson: String): Boolean {
            val service = instance

            if (service == null) {
                Log.e("GhostService", "Accessibility service is not enabled")
                return false
            }

            return try {
                val obj = JSONObject(actionJson)
                val action = obj.optString("action")

                when (action) {
                    "tap" -> {
                        val x = obj.optDouble("x", -1.0).toFloat()
                        val y = obj.optDouble("y", -1.0).toFloat()

                        if (x < 0 || y < 0) {
                            Log.e("GhostService", "Invalid tap coordinates: $x, $y")
                            return false
                        }

                        service.performTap(x, y)
                        true
                    }

                    "swipe" -> {
                        val direction = obj.optString("direction", "")
                        service.performDirectionalSwipe(direction)
                        true
                    }

                    "type" -> {
                        val text = obj.optString("text", "")
                        service.performType(text)
                    }

                    "wait" -> {
                        Thread.sleep(1000)
                        true
                    }

                    "done" -> {
                        Log.i("GhostService", "Goal already done")
                        true
                    }

                    "ask_user" -> {
                        val reason = obj.optString("reason", "Need user confirmation")
                        Log.w("GhostService", "Ask user: $reason")
                        false
                    }

                    else -> {
                        Log.e("GhostService", "Unknown action: $action")
                        false
                    }
                }

            } catch (e: Exception) {
                Log.e("GhostService", "Failed to execute action JSON", e)
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("GhostService", "Accessibility service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i("GhostService", "Accessibility service destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.i("GhostService", "Accessibility service interrupted")
    }

    private fun takeScreenshotAsJpegBytes(): ByteArray? {
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

                            resultBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)

                            hardwareBitmap?.recycle()
                            screenshot.hardwareBuffer.close()

                        } catch (e: Exception) {
                            Log.e("GhostService", "Screenshot processing failed", e)
                        }

                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e("GhostService", "Screenshot failed with code: $errorCode")
                        latch.countDown()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("GhostService", "takeScreenshot threw error", e)
            return null
        }

        val completed = latch.await(5, TimeUnit.SECONDS)

        if (!completed) {
            Log.e("GhostService", "Screenshot timeout")
            return null
        }

        val bitmap = resultBitmap ?: return null

        return try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            bitmap.recycle()

            val bytes = outputStream.toByteArray()
            Log.i("GhostService", "Screenshot captured: ${bytes.size / 1024}KB")
            bytes

        } catch (e: Exception) {
            Log.e("GhostService", "JPEG encoding failed", e)
            bitmap.recycle()
            null
        }
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        dispatchGesture(gesture, null, null)
        Log.i("GhostService", "Tap requested at $x, $y")
    }

    private fun performDirectionalSwipe(direction: String) {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()

        val centerX = width / 2f
        val centerY = height / 2f

        val startX: Float
        val startY: Float
        val endX: Float
        val endY: Float

        when (direction) {
            "up" -> {
                startX = centerX
                startY = height * 0.75f
                endX = centerX
                endY = height * 0.30f
            }

            "down" -> {
                startX = centerX
                startY = height * 0.30f
                endX = centerX
                endY = height * 0.75f
            }

            "left" -> {
                startX = width * 0.80f
                startY = centerY
                endX = width * 0.20f
                endY = centerY
            }

            "right" -> {
                startX = width * 0.20f
                startY = centerY
                endX = width * 0.80f
                endY = centerY
            }

            else -> {
                Log.e("GhostService", "Invalid swipe direction: $direction")
                return
            }
        }

        performSwipe(startX, startY, endX, endY, 500)
    }

    private fun performSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        dispatchGesture(gesture, null, null)
        Log.i("GhostService", "Swipe requested from ($startX,$startY) to ($endX,$endY)")
    }

    private fun performType(text: String): Boolean {
        var targetNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (targetNode == null) {
            targetNode = findEditableNode(rootInActiveWindow)
        }

        if (targetNode == null) {
            Log.e("GhostService", "No editable input found")
            return false
        }

        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )

        val success = targetNode.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            args
        )

        targetNode.recycle()

        return success
    }

    private fun findEditableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null

        if (root.isEditable) {
            return root
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findEditableNode(child)

            if (result != null) {
                return result
            }

            child.recycle()
        }

        return null
    }
}