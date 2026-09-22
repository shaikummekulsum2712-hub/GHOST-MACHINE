package com.example.ghostmachine

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

class ScreenObserver(private val service: AccessibilityService) {
    data class Element(
        val text: String?,
        val description: String?,
        val resourceId: String?,
        val clickable: Boolean,
        val editable: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    data class Snapshot(
        val packageName: String?,
        val elements: List<Element>,
        val signature: String,
        val screenType: String
    )

    fun observe(): Snapshot {
        val root = service.rootInActiveWindow
            ?: return Snapshot(null, emptyList(), "none", "unknown")

        val elements = mutableListOf<Element>()

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || elements.size >= 150) return

            try {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val text = node.text?.toString()
                val description = node.contentDescription?.toString()
                val resourceId = try { node.viewIdResourceName } catch (_: Exception) { null }
                val className = node.className?.toString().orEmpty()
                val clickable = try { node.isClickable } catch (_: Exception) { false }
                val editable = try {
                    node.isEditable || className.contains("EditText", ignoreCase = true)
                } catch (_: Exception) {
                    className.contains("EditText", ignoreCase = true)
                }

                if (rect.width() > 5 && rect.height() > 5 &&
                    (!text.isNullOrBlank() || !description.isNullOrBlank() || clickable || editable)) {
                    elements += Element(
                        text, description, resourceId, clickable, editable,
                        rect.left, rect.top, rect.right, rect.bottom
                    )
                }

                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (_: Exception) { null }
                    try {
                        visit(child)
                    } finally {
                        child?.recycle()
                    }
                }
            } catch (_: Exception) {
                // UI trees can mutate while being traversed. The next observe
                // will obtain a fresh root and retry rather than retaining nodes.
            }
        }

        visit(root)

        val packageName = root.packageName?.toString()
        val blob = elements.joinToString("|") {
            listOf(
                it.text.orEmpty(), it.description.orEmpty(), it.resourceId.orEmpty(),
                it.clickable, it.editable, it.left, it.top, it.right, it.bottom
            ).joinToString("~")
        }

        val screenType = when {
            elements.any { it.editable } && elements.any {
                (it.text.orEmpty() + " " + it.description.orEmpty()).contains("send", true)
            } -> "message"
            elements.any {
                (it.text.orEmpty() + " " + it.description.orEmpty()).contains("search", true)
            } -> "search"
            elements.any {
                (it.text.orEmpty() + " " + it.description.orEmpty()).contains("call", true)
            } -> "call"
            else -> "generic"
        }

        return Snapshot(
            packageName = packageName,
            elements = elements,
            signature = "${packageName.orEmpty()}:${blob.hashCode()}",
            screenType = screenType
        )
    }
}
