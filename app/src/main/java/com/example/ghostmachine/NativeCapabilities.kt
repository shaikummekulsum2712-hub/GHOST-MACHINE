package com.example.ghostmachine

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import java.util.concurrent.Executors

/** Native operations that must never require the planner or VLM. */
object NativeCapabilities {
    private const val TAG = "NativeCapabilities"

    fun handle(service: AccessibilityService, command: String): Boolean {
        val lower = command.lowercase().trim()
        if (lower.isBlank()) return false

        // Global Android controls.
        if (lower == "home" || lower == "go home") {
            val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            Log.d(TAG, "HOME native=$ok")
            return ok
        }

        if (lower == "back" || lower == "go back") {
            val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Log.d(TAG, "BACK native=$ok")
            return ok
        }

        if (lower == "screenshot" || lower == "take screenshot" || lower == "capture screenshot" || lower == "ss") {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Log.e(TAG, "Screenshot requires Android 11+")
                return false
            }
            val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            Log.d(TAG, "SCREENSHOT native=$ok")
            return ok
        }

        // Timer.
        if (Regex("\\b(?:set )?(?:a )?timer\\b").containsMatchIn(lower)) {
            val seconds = Regex("(\\d+)\\s*(seconds?|secs?)").find(lower)?.groupValues?.get(1)?.toLongOrNull()
            val minutes = Regex("(\\d+)\\s*(minutes?|mins?)").find(lower)?.groupValues?.get(1)?.toLongOrNull()
            val duration = seconds ?: minutes?.times(60L)
            if (duration != null && duration in 1..Int.MAX_VALUE.toLong()) {
                return try {
                    service.startActivity(Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, duration.toInt())
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    Log.d(TAG, "TIMER native duration=$duration")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Timer failed", e)
                    false
                }
            }
        }

        // Camera: opening the camera is also native; do not resolve arbitrary
        // installed packages whose labels merely contain the word camera.
        if (lower.trim() in setOf(
                "open camera", "camera", "take picture", "take photo",
                "take a picture", "take a photo", "capture a picture"
            )) {
            return try {
                // 1. Primary: Standard Still Image Camera Intent
                val stillCameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                // 2. Fallback A: Main Camera Category (Using raw String to avoid unresolved reference)
                val categoryCameraIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory("android.intent.category.APP_CAMERA")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                // 3. Fallback B: Common OEM Camera Package Names
                val cameraPackageNames = listOf(
                    "com.android.camera",              // AOSP / Stock / Emulator
                    "com.android.camera2",             // AOSP Camera2
                    "com.google.android.GoogleCamera", // Pixel / Nexus
                    "com.sec.android.app.camera",      // Samsung
                    "com.huawei.camera",               // Huawei
                    "com.oneplus.camera",              // OnePlus
                    "com.miui.camera"                  // Xiaomi / Redmi
                )

                val launched = runCatching { service.startActivity(stillCameraIntent) }.isSuccess ||
                        runCatching { service.startActivity(categoryCameraIntent) }.isSuccess ||
                        cameraPackageNames.any { pkg ->
                            val pkgIntent = service.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            if (pkgIntent != null) {
                                runCatching { service.startActivity(pkgIntent) }.isSuccess
                            } else false
                        }

                if (launched) {
                    Log.d(TAG, "CAMERA native dispatched successfully")
                    true
                } else {
                    Log.e(TAG, "No camera app found on device")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera dispatch failed", e)
                false
            }
        }

        // Calendar app launch and event creation.
        if (lower == "open calendar" || lower == "calendar") {
            return try {
                val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(intent)
                Log.d(TAG, "CALENDAR native dispatched")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Calendar launch failed", e)
                false
            }
        }

        if ((lower.contains("create") || lower.contains("add") || lower.contains("schedule")) &&
            (lower.contains("calendar") || lower.contains("event"))) {
            return try {
                service.startActivity(Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, extractEventTitle(command))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                Log.d(TAG, "CALENDAR EVENT native dispatched")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Calendar event failed", e)
                false
            }
        }

        if (lower == "open settings" || lower == "open system settings") {
            return try {
                service.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                Log.d(TAG, "SETTINGS native dispatched")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Settings failed", e)
                false
            }
        }

        if (lower == "open browser") {
            return try {
                service.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                Log.d(TAG, "BROWSER native dispatched")
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
                Log.d(TAG, "MAPS native dispatched")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Maps failed", e)
                false
            }
        }

        if (lower == "open phone" || lower == "open dialer") {
            return openDialer(service)
        }

        // Normal phone calls. A number can be called directly without UI
        // automation. Named contacts still use the service's UI flow.
        if (lower.startsWith("call ")) {
            val number = extractPhoneNumber(command)
            if (number != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    service.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(TAG, "CALL_PHONE permission missing; opening dialer instead")
                    return try {
                        service.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Dialer fallback failed", e)
                        false
                    }
                }
                return try {
                    service.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    Log.d(TAG, "CALL native dispatched number=$number")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Native call failed", e)
                    false
                }
            }
        }

        return false
    }

    private fun openDialer(service: AccessibilityService): Boolean = try {
        service.startActivity(Intent(Intent.ACTION_DIAL).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        Log.d(TAG, "DIALER native dispatched")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Dialer failed", e)
        false
    }

    private fun extractPhoneNumber(command: String): String? {
        val match = Regex("(?:call|dial)\\s+([+\\d][+\\d\\s().-]{5,})", RegexOption.IGNORE_CASE).find(command)
            ?: return null
        val digits = match.groupValues[1].filter { it.isDigit() || it == '+' }
        return digits.takeIf { it.count { c -> c.isDigit() } >= 7 }
    }

    private fun extractEventTitle(command: String): String {
        val match = Regex("\\b(?:called|named)\\s+(.+)", RegexOption.IGNORE_CASE).find(command)
        return match?.groupValues?.getOrNull(1)?.trim().takeUnless { it.isNullOrBlank() } ?: "Ghost Machine Event"
    }
}
