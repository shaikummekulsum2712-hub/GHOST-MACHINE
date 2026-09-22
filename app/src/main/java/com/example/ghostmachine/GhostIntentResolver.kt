package com.example.ghostmachine

object GhostIntentResolver {



    data class ResolvedCommand(
        val intent: String,
        val target: String,
        val isCompound: Boolean
    )

    private fun match(
        pattern: String,
        text: String
    ): MatchResult? {
        return Regex(
            pattern,
            RegexOption.IGNORE_CASE
        ).find(text)
    }

    fun resolve(command: String): ResolvedCommand {
        // Clean up punctuation and common voice assistant fillers
        val normalized = command
            .trim()
            .replace(Regex("[.,!?]+$"), "") // Trim trailing punctuation
            .replace(Regex("(?i)^\\b(please|can you|could you)\\b\\s*"), "") // Trim leading politeness
            .replace(Regex("(?i)\\s*\\bplease\\b$"), "") // Trim trailing politeness
            .replace(Regex("\\s+"), " ")
            .trim()

        val lower = normalized.lowercase()

        if (lower.isBlank()) {
            return ResolvedCommand("unknown", "", false)
        }

        // Handle app deletion / uninstall requests with a safety restriction response
        val lowerCommand = normalized.lowercase()
        if (
            lowerCommand.contains("uninstall") ||
            lowerCommand.contains("delete app") ||
            lowerCommand.contains("remove app") ||
            lowerCommand.contains("delete this app")
        ) {
            return ResolvedCommand(
                intent = "safety_block",
                target = "Deleting applications is restricted due to security and safety hazard policies.",
                isCompound = false
            )
        }

        // -------------------------------------------------------------
        // BASIC SYSTEM COMMANDS
        // -------------------------------------------------------------

        when (lower) {
            "stop", "cancel" -> return ResolvedCommand("stop", "", false)
            "back", "go back" -> return ResolvedCommand("back", "", false)
            "home", "go home", "home screen" -> return ResolvedCommand("home", "", false)
            "screenshot", "take screenshot", "capture screenshot" -> return ResolvedCommand("screenshot", "", false)
        }

        // -------------------------------------------------------------
        // SCROLL / SWIPE
        // -------------------------------------------------------------

        match("^(?:scroll|swipe)\\s+(up|down|left|right)$", normalized)?.let {
            return ResolvedCommand("scroll", it.groupValues[1].lowercase(), false)
        }

        // -------------------------------------------------------------
        // CAMERA
        // -------------------------------------------------------------

        if (
            lower == "take picture" ||
            lower == "take photo" ||
            lower == "take a picture" ||
            lower == "take a photo" ||
            lower.contains("capture a picture") ||
            lower.contains("capture a photo")
        ) {
            return ResolvedCommand("camera", "", false)
        }

        // -------------------------------------------------------------
        // TIMER
        // -------------------------------------------------------------

        if (
            lower.contains("timer") &&
            Regex("\\d+\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)").containsMatchIn(lower)
        ) {
            return ResolvedCommand("timer", normalized, false)
        }

        // -------------------------------------------------------------
        // CALENDAR
        // -------------------------------------------------------------

        if (
            (lower.contains("calendar") || lower.contains("event")) &&
            (lower.contains("create") || lower.contains("add") || lower.contains("schedule") || lower.contains("make"))
        ) {
            return ResolvedCommand("calendar", normalized, false)
        }

        // -------------------------------------------------------------
        // TYPE + SEND
        // -------------------------------------------------------------

        match("^(?:type|write|enter)\\s+(.+?)\\s+(?:and\\s+)?send(?:\\s+it)?$", normalized)?.let {
            val message = it.groupValues[1].trim()
            if (message.isNotBlank()) {
                return ResolvedCommand("type_and_send", message, false)
            }
        }

        // -------------------------------------------------------------
        // DIRECT MESSAGE
        // -------------------------------------------------------------

        match("^(?:message|text)\\s+(.+?)\\s+(?:saying|that)\\s+(.+)$", normalized)?.let {
            val contact = it.groupValues[1].trim()
            val message = it.groupValues[2].trim()

            if (contact.isNotBlank() && message.isNotBlank()) {
                return ResolvedCommand("direct_message", "$contact|||$message", false)
            }
        }

        // -------------------------------------------------------------
        // MESSAGE HIM / HER
        // -------------------------------------------------------------

        match("^(?:message|text|send)\\s+(?:him|her|them)\\s+(.+)$", normalized)?.let {
            val message = it.groupValues[1].trim()
            if (message.isNotBlank()) {
                return ResolvedCommand("type_and_send", message, false)
            }
        }

        // -------------------------------------------------------------
        // OPEN CHAT + COMPOUND
        // -------------------------------------------------------------

        match("^(?:open\\s+)?(?:a\\s+)?chat\\s+(?:with\\s+)?(.+?)\\s+and\\s+(?:message|text|send|type|call)\\b.*$", normalized)?.let {
            val contact = it.groupValues[1].trim()
            if (contact.isNotBlank()) {
                return ResolvedCommand("open_chat", contact, true)
            }
        }

        // -------------------------------------------------------------
        // OPEN CHAT
        // -------------------------------------------------------------

        match("^(?:open\\s+)?(?:a\\s+)?chat\\s+(?:with\\s+)?(.+)$", normalized)?.let {
            val contact = it.groupValues[1].trim()
            if (contact.isNotBlank()) {
                return ResolvedCommand("open_chat", contact, false)
            }
        }

        // -------------------------------------------------------------
        // VIDEO CALL
        // -------------------------------------------------------------

        match("^video\\s+(?:call|chat)\\s+(.+)$", normalized)?.let {
            val target = it.groupValues[1].trim()
            if (target.isNotBlank()) {
                return ResolvedCommand("video_call", target, false)
            }
        }

        // -------------------------------------------------------------
        // CALL
        // -------------------------------------------------------------

        match("^call\\s+(.+)$", normalized)?.let {
            val target = it.groupValues[1].trim()
            if (target.isNotBlank()) {
                return ResolvedCommand("call", target, false)
            }
        }

        // -------------------------------------------------------------
        // SEARCH
        // -------------------------------------------------------------

        match("^(?:search|find|look(?:\\s+up)?)\\s+(?:for\\s+)?(.+)$", normalized)?.let {
            val target = it.groupValues[1].trim()
            if (target.isNotBlank()) {
                return ResolvedCommand("search", target, false)
            }
        }

        // -------------------------------------------------------------
        // TAP
        // -------------------------------------------------------------

        match("^(?:tap|click|press|select)\\s+(.+)$", normalized)?.let {
            val target = it.groupValues[1].trim()
            if (target.isNotBlank()) {
                return ResolvedCommand("tap", target, false)
            }
        }

        // -------------------------------------------------------------
        // TYPE
        // -------------------------------------------------------------

        match("^(?:type|write|enter)\\s+(.+)$", normalized)?.let {
            val target = it.groupValues[1].trim()
            if (target.isNotBlank()) {
                return ResolvedCommand("type", target, false)
            }
        }

        // -------------------------------------------------------------
        // SEND
        // -------------------------------------------------------------

        if (lower == "send" || lower == "send it") {
            return ResolvedCommand("send", "", false)
        }

        match("^send\\s+(.+)$", normalized)?.let {
            val target = it.groupValues[1].trim()
            if (target.isNotBlank()) {
                return ResolvedCommand("type_and_send", target, false)
            }
        }

        // -------------------------------------------------------------
        // OPEN APP
        // -------------------------------------------------------------

        match("^open\\s+(.+)$", normalized)?.let {
            val target = it.groupValues[1].trim()
            if (target.isNotBlank()) {
                return ResolvedCommand("open", target, false)
            }
        }

        // -------------------------------------------------------------
        // COMPOUND COMMAND DETECTION
        // -------------------------------------------------------------

        val hasExplicitCompoundConnector = Regex("\\s+(?:and|then)\\s+", RegexOption.IGNORE_CASE).containsMatchIn(normalized) || normalized.contains(",")
        val hasMultipleCommandClauses = hasMultipleCommandClauses(normalized)

        if (hasExplicitCompoundConnector && hasMultipleCommandClauses) {
            return ResolvedCommand("compound", normalized, true)
        }

        // -------------------------------------------------------------
        // OTHER MULTI-GOAL LANGUAGE
        // -------------------------------------------------------------

        val compoundIndicators = listOf(
            "find me", "find the best", "show me", "compare", "cheapest", "recommend", "browse"
        )

        if (compoundIndicators.any { lower.contains(it) }) {
            return ResolvedCommand("compound", normalized, true)
        }

        // -------------------------------------------------------------
        // UNKNOWN
        // -------------------------------------------------------------

        return ResolvedCommand("unknown", normalized, false)
    }

    private fun hasMultipleCommandClauses(command: String): Boolean {
        val lower = command.lowercase()
        val clauses = lower.split(Regex("\\s+(?:and|then|,)\\s+"))

        if (clauses.size < 2) return false

        val actionVerbs = setOf(
            "open", "search", "find", "look", "message", "text", "send", "call",
            "type", "write", "tap", "click", "press", "select", "swipe", "scroll"
        )

        // Count how many split clauses start with a known action verb
        val clauseMatchCount = clauses.count { clause ->
            val firstWord = clause.trim().split("\\s+".toRegex()).firstOrNull() ?: ""
            firstWord in actionVerbs
        }

        return clauseMatchCount >= 2
    }
}