package com.example.ghostmachine

class ActionVerifier {
    fun textVisible(elements: List<GhostAccessibilityService.UiElement>, expected: String): Boolean {
        val wanted = normalize(expected)
        return wanted.isNotBlank() && elements.any { normalize(it.text.orEmpty()) == wanted }
    }

    fun textInEditable(elements: List<GhostAccessibilityService.UiElement>, expected: String): Boolean {
        val wanted = normalize(expected)
        return wanted.isNotBlank() && elements.any {
            it.editable && normalize(it.text.orEmpty()) == wanted
        }
    }

    fun foregroundIs(actualPackage: String?, expectedPackage: String?): Boolean =
        !actualPackage.isNullOrBlank() &&
                !expectedPackage.isNullOrBlank() &&
                actualPackage == expectedPackage

    fun screenChanged(before: String?, after: String?): Boolean =
        before != null && after != null && before != after

    fun normalize(value: String): String =
        value.lowercase().replace(Regex("\\s+"), " ").trim()
}
