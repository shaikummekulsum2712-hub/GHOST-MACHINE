package com.example.ghostmachine

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

class ScreenObserver(
    private val service: AccessibilityService
) {

    companion object {
        private const val MAX_ELEMENTS = 300
        private val SEARCH_REGEX = Regex("\\bsearch\\b", RegexOption.IGNORE_CASE)
        private val SEND_REGEX = Regex("\\bsend\\b", RegexOption.IGNORE_CASE)
        private val CALL_REGEX = Regex("\\bcall\\b", RegexOption.IGNORE_CASE)
    }

    data class Element(
        val text: String?,
        val description: String?,
        val resourceId: String?,
        val className: String?,
        val clickable: Boolean,
        val editable: Boolean,
        val enabled: Boolean,
        val focused: Boolean,
        val scrollable: Boolean,
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
            ?: return Snapshot(
                packageName = null,
                elements = emptyList(),
                signature = "none",
                screenType = "unknown"
            )

        val packageName = try {
            root.packageName?.toString()
        } catch (_: Exception) {
            null
        }

        val elements = mutableListOf<Element>()

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return

            try {
                if (elements.size < MAX_ELEMENTS) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)

                    val text = try { node.text?.toString() } catch (_: Exception) { null }
                    val description = try { node.contentDescription?.toString() } catch (_: Exception) { null }
                    val resourceId = try { node.viewIdResourceName } catch (_: Exception) { null }
                    val className = try { node.className?.toString() } catch (_: Exception) { null }
                    val clickable = try { node.isClickable } catch (_: Exception) { false }

                    val editable = try {
                        node.isEditable || className?.contains("EditText", ignoreCase = true) == true
                    } catch (_: Exception) {
                        className?.contains("EditText", ignoreCase = true) == true
                    }

                    val enabled = try { node.isEnabled } catch (_: Exception) { false }
                    val focused = try { node.isFocused } catch (_: Exception) { false }
                    val scrollable = try { node.isScrollable } catch (_: Exception) { false }

                    if (
                        rect.width() > 5 &&
                        rect.height() > 5 &&
                        (
                                !text.isNullOrBlank() ||
                                        !description.isNullOrBlank() ||
                                        !resourceId.isNullOrBlank() ||
                                        clickable ||
                                        editable ||
                                        scrollable
                                )
                    ) {
                        elements += Element(
                            text = text,
                            description = description,
                            resourceId = resourceId,
                            className = className,
                            clickable = clickable,
                            editable = editable,
                            enabled = enabled,
                            focused = focused,
                            scrollable = scrollable,
                            left = rect.left,
                            top = rect.top,
                            right = rect.right,
                            bottom = rect.bottom
                        )
                    }
                }

                val childCount = try { node.childCount } catch (_: Exception) { 0 }

                for (i in 0 until childCount) {
                    // Stop spawning child node references if max elements limit is hit
                    if (elements.size >= MAX_ELEMENTS) break

                    val child = try { node.getChild(i) } catch (_: Exception) { null }
                    try {
                        visit(child)
                    } finally {
                        child?.recycle()
                    }
                }
            } catch (_: Exception) {
                // Accessibility tree can mutate dynamically during traversal
            }
        }

        try {
            visit(root)
        } finally {
            root.recycle()
        }

        val screenType = classifyScreen(elements)

        val signatureBlob = buildString {
            append(packageName.orEmpty()).append('|').append(screenType).append('|')
            elements.forEach { element ->
                append(element.text.orEmpty()).append('~')
                append(element.description.orEmpty()).append('~')
                append(element.resourceId.orEmpty()).append('~')
                append(element.className.orEmpty()).append('~')
                append(element.clickable).append('~')
                append(element.editable).append('~')
                append(element.focused).append('|')
            }
        }

        return Snapshot(
            packageName = packageName,
            elements = elements,
            signature = "${packageName.orEmpty()}:${signatureBlob.hashCode()}",
            screenType = screenType
        )
    }

    private fun classifyScreen(elements: List<Element>): String {
        var hasEditable = false
        var hasSearch = false
        var hasSend = false
        var hasCall = false
        var hasScrollable = false
        var hasAnyText = false

        for (element in elements) {
            if (element.editable) hasEditable = true
            if (element.scrollable) hasScrollable = true

            val textAndDesc = "${element.text.orEmpty()} ${element.description.orEmpty()}"
            val combinedInfo = "$textAndDesc ${element.resourceId.orEmpty()}"

            if (textAndDesc.isNotBlank()) {
                hasAnyText = true
            }

            if (!hasSearch && SEARCH_REGEX.containsMatchIn(combinedInfo)) {
                hasSearch = true
            }
            if (!hasSend && SEND_REGEX.containsMatchIn(combinedInfo)) {
                hasSend = true
            }
            if (!hasCall && CALL_REGEX.containsMatchIn(combinedInfo)) {
                hasCall = true
            }
        }

        return when {
            hasEditable && hasSend -> "message"
            hasSearch -> "search"
            hasCall -> "call"
            hasEditable -> "input"
            hasScrollable && !hasAnyText -> "scrollable"
            else -> "generic"
        }
    }
}