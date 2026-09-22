package com.example.ghostmachine

object GhostIntentResolver {
    data class ResolvedCommand(val intent: String, val target: String, val isCompound: Boolean)

    private fun match(pattern: String, text: String): MatchResult? = Regex(pattern, RegexOption.IGNORE_CASE).find(text)

    fun resolve(command: String): ResolvedCommand {
        val normalized = command.trim().replace(Regex("\\s+"), " ")
        val lower = normalized.lowercase()

        if (lower == "stop" || lower == "cancel") return ResolvedCommand("stop", "", false)
        if (lower == "back" || lower == "go back") return ResolvedCommand("back", "", false)
        if (lower == "screenshot" || lower == "take screenshot" || lower == "capture screenshot") {
            return ResolvedCommand("screenshot", "", false)
        }
        if (lower.contains("take a picture") || lower.contains("take a photo") ||
            lower == "take picture" || lower == "take photo" || lower.contains("capture a picture")) {
            return ResolvedCommand("camera", "", false)
        }
        if (lower.contains("timer") && Regex("\\d+\\s*(seconds?|secs?|minutes?|mins?)").containsMatchIn(lower)) {
            return ResolvedCommand("timer", lower, false)
        }
        if ((lower.contains("calendar") || lower.contains("event")) &&
            (lower.contains("create") || lower.contains("add") || lower.contains("schedule"))) {
            return ResolvedCommand("calendar", lower, false)
        }
        if (lower == "home" || lower == "go home" || lower == "home screen") return ResolvedCommand("home", "", false)
        match("^(?:scroll|swipe)\\s+(up|down|left|right)$", normalized)?.let { return ResolvedCommand("scroll", it.groupValues[1].lowercase(), false) }

        match("^(?:message|text)\\s+(.+?)\\s+(?:saying|that)\\s+(.+)$", normalized)?.let {
            return ResolvedCommand("direct_message", "${it.groupValues[1].trim()}|||${it.groupValues[2].trim()}", false)
        }
        match("^(?:message|text)\\s+(?:him|her|them)\\s+(.+)$", normalized)?.let {
            return ResolvedCommand("type_and_send", it.groupValues[1].trim(), false)
        }
        match("^(?:open\\s+)?(?:a\\s+)?chat\\s+(?:with\\s+)?(.+?)\\s+and\\s+(?:message|text|send|type|call)\\b.*$", normalized)?.let {
            return ResolvedCommand("open_chat", it.groupValues[1].trim(), true)
        }
        match("^(?:open\\s+)?(?:a\\s+)?chat\\s+(?:with\\s+)?(.+)$", normalized)?.let {
            return ResolvedCommand("open_chat", it.groupValues[1].trim(), false)
        }
        match("^(?:type|write|enter)\\s+(.+?)\\s+(?:and\\s+)?send(?:\\s+it)?$", normalized)?.let {
            return ResolvedCommand("type_and_send", it.groupValues[1].trim(), false)
        }
        match("^send(?:\\s+(?:it|him|her))?\\s+(.+)$", normalized)?.let {
            return ResolvedCommand("type_and_send", it.groupValues[1].trim(), false)
        }
        match("^(?:search|find|look(?:\\s+up)?)\\s+(?:for\\s+)?(.+)$", normalized)?.let {
            return ResolvedCommand("search", it.groupValues[1].trim(), false)
        }
        match("^video\\s+(?:call|chat)\\s+(.+)$", normalized)?.let { return ResolvedCommand("video_call", it.groupValues[1].trim(), false) }
        match("^call\\s+(.+)$", normalized)?.let { return ResolvedCommand("call", it.groupValues[1].trim(), false) }
        match("^(?:tap|click|press|select)\\s+(.+)$", normalized)?.let { return ResolvedCommand("tap", it.groupValues[1].trim(), false) }
        match("^(?:type|write|enter)\\s+(.+)$", normalized)?.let { return ResolvedCommand("type", it.groupValues[1].trim(), false) }
        match("^open\\s+(.+)$", normalized)?.let { return ResolvedCommand("open", it.groupValues[1].trim(), false) }
        if (lower == "send" || lower == "send it") return ResolvedCommand("send", "", false)

        val compound = lower.contains(" and ") || lower.contains(" then ") || lower.contains(",") ||
                listOf("find me", "find the best", "show me", "compare", "cheapest", "recommend", "browse").any { lower.contains(it) }
        return ResolvedCommand("unknown", normalized, compound)
    }
}
