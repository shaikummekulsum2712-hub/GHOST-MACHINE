package com.example.ghostmachine

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/**
 * Android-native capabilities that should not require the planner, Jev, or VLM.
 * UI/app automation remains in GhostAccessibilityService.
 */
object NativeCapabilities {
    private const val TAG = "NativeCapabilities"

    fun handle(
        service: AccessibilityService,
        command: String,
        onCameraOpened: (() -> Unit)? = null
    ): Boolean {
        val lower = command.lowercase().trim()

        if (lower.matches(Regex(".*\\b(?:set )?(?:a )?timer\\b.*"))) {
            val seconds = Regex("(\\d+)\\s*(seconds?|secs?)").find(lower)?.groupValues?.get(1)?.toLongOrNull()
            val minutes = Regex("(\\d+)\\s*(minutes?|mins?)").find(lower)?.groupValues?.get(1)?.toLongOrNull()
            val duration = seconds ?: minutes?.times(60L)

            if (duration != null && duration > 0 && duration <= Int.MAX_VALUE) {
                return try {
                    service.startActivity(Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, duration.toInt())
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Timer failed", e)
                    false
                }
            }
        }

        if (lower.contains("take a picture") || lower.contains("take a photo") ||
            lower.contains("capture a picture") || lower == "take picture" || lower == "take photo") {
            return try {
                service.startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                onCameraOpened?.invoke()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Camera failed", e)
                false
            }
        }

        if ((lower.contains("create") || lower.contains("add") || lower.contains("schedule")) &&
            (lower.contains("calendar") || lower.contains("event"))) {
            return try {
                val title = when {
                    Regex("\\bcalled\\s+(.+)", RegexOption.IGNORE_CASE).find(command) != null ->
                        Regex("\\bcalled\\s+(.+)", RegexOption.IGNORE_CASE).find(command)?.groupValues?.get(1)
                    Regex("\\bnamed\\s+(.+)", RegexOption.IGNORE_CASE).find(command) != null ->
                        Regex("\\bnamed\\s+(.+)", RegexOption.IGNORE_CASE).find(command)?.groupValues?.get(1)
                    else -> "Ghost Machine Event"
                }.orEmpty().trim().ifBlank { "Ghost Machine Event" }

                service.startActivity(Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, title)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            } catch (e: Exception) {
                Log.e(TAG, "Calendar failed", e)
                false
            }
        }

        if (lower == "open settings" || lower == "open system settings") {
            return try {
                service.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            } catch (e: Exception) {
                Log.e(TAG, "Settings failed", e)
                false
            }
        }

        if (lower == "open browser" || lower == "open chrome") {
            return try {
                service.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            } catch (e: Exception) {
                Log.e(TAG, "Browser failed", e)
                false
            }
        }

        if (lower == "open maps" || lower == "open google maps") {
            return try {
                service.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            } catch (e: Exception) {
                Log.e(TAG, "Maps failed", e)
                false
            }
        }

        if (lower == "open phone" || lower == "open dialer") {
            return try {
                service.startActivity(Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            } catch (e: Exception) {
                Log.e(TAG, "Dialer failed", e)
                false
            }
        }

        if (lower == "take screenshot" || lower == "capture screenshot" || lower == "screenshot") {
            return takeScreenshot(service)
        }

        return false
    }

    private fun takeScreenshot(service: AccessibilityService): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Accessibility screenshot requires Android 11+")
            return false
        }

        return try {
            val latch = CountDownLatch(1)
            var saved = false
            val executor = Executors.newSingleThreadExecutor()

            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            if (hardwareBitmap != null) {
                                val bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                hardwareBitmap.recycle()
                                if (bitmap != null) {
                                    saveScreenshot(service, bitmap)
                                    bitmap.recycle()
                                    saved = true
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Saving screenshot failed", e)
                        } finally {
                            result.hardwareBuffer.close()
                            latch.countDown()
                            executor.shutdown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed: $errorCode")
                        latch.countDown()
                        executor.shutdown()
                    }
                }
            )

            val completed = latch.await(5, TimeUnit.SECONDS)
            completed && saved
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot failed", e)
            false
        }
    }

    private fun saveScreenshot(context: Context, bitmap: Bitmap) {
        val name = "GhostMachine_${System.currentTimeMillis()}.png"
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GhostMachine")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create screenshot MediaStore entry")

        try {
            resolver.openOutputStream(uri)?.use { output: OutputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            } ?: throw IllegalStateException("Could not open screenshot output")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            Log.d(TAG, "Screenshot saved: $uri")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
