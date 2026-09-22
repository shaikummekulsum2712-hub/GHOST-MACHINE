package com.example.ghostmachine

class ActionVerifier {

    companion object {
        private val MULTI_SPACE_REGEX = Regex("\\s+")
    }

    fun textVisible(
        elements: List<ScreenObserver.Element>,
        expected: String
    ): Boolean {
        val wanted = normalize(expected)
        if (wanted.isBlank()) return false

        return elements.any {
            normalize(it.text.orEmpty()) == wanted ||
                    normalize(it.description.orEmpty()) == wanted
        }
    }

    fun textContains(
        elements: List<ScreenObserver.Element>,
        expected: String
    ): Boolean {
        val wanted = normalize(expected)
        if (wanted.isBlank()) return false

        return elements.any {
            normalize(it.text.orEmpty()).contains(wanted) ||
                    normalize(it.description.orEmpty()).contains(wanted)
        }
    }

    fun textInEditable(
        elements: List<ScreenObserver.Element>,
        expected: String
    ): Boolean {
        val wanted = normalize(expected)
        if (wanted.isBlank()) return false

        return elements.any {
            it.editable && normalize(it.text.orEmpty()).contains(wanted)
        }
    }

    fun resourceVisible(
        elements: List<ScreenObserver.Element>,
        resourceId: String
    ): Boolean {
        val wanted = normalize(resourceId)
        if (wanted.isBlank()) return false

        return elements.any {
            val elementResId = normalize(it.resourceId.orEmpty())
            elementResId == wanted || elementResId.endsWith(":id/$wanted") || elementResId.endsWith("/$wanted")
        }
    }

    fun editableExists(
        elements: List<ScreenObserver.Element>
    ): Boolean {
        return elements.any { it.editable && it.enabled }
    }

    fun clickableExists(
        elements: List<ScreenObserver.Element>,
        expected: String
    ): Boolean {
        val wanted = normalize(expected)
        if (wanted.isBlank()) return false

        return elements.any {
            if (!it.clickable) return@any false

            val text = normalize(it.text.orEmpty())
            val desc = normalize(it.description.orEmpty())
            val resId = normalize(it.resourceId.orEmpty())

            text == wanted ||
                    desc == wanted ||
                    resId == wanted ||
                    resId.endsWith(":id/$wanted") ||
                    resId.endsWith("/$wanted")
        }
    }

    fun foregroundIs(
        actualPackage: String?,
        expectedPackage: String?
    ): Boolean {
        return !actualPackage.isNullOrBlank() &&
                !expectedPackage.isNullOrBlank() &&
                actualPackage.equals(expectedPackage, ignoreCase = true)
    }

    fun screenChanged(
        before: String?,
        after: String?
    ): Boolean {
        return !before.isNullOrBlank() &&
                !after.isNullOrBlank() &&
                before != after
    }

    fun screenTypeIs(
        snapshot: ScreenObserver.Snapshot?,
        expected: String
    ): Boolean {
        return snapshot?.screenType?.equals(expected, ignoreCase = true) == true
    }

    fun hasElement(
        elements: List<ScreenObserver.Element>,
        predicate: (ScreenObserver.Element) -> Boolean
    ): Boolean {
        return elements.any(predicate)
    }

    fun normalize(value: String): String {
        if (value.isBlank()) return ""
        return value
            .lowercase()
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }
}