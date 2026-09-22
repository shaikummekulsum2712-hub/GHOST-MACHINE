//package com.example.ghostmachine
//
//object VoiceLanguageManager {
//
//    data class VoiceCommandContext(
//        val originalCommand: String,
//        val normalizedCommand: String,
//        val parsedIntent: String,
//        val parsedTarget: String,
//        val replyLanguage: String = "english"
//    )
//
//    fun buildContext(command: String): VoiceCommandContext {
//        val original = command.trim()
//        val normalized = normalizeCommand(original)
//        val parsed = parseNormalizedCommand(normalized)
//
//        return VoiceCommandContext(
//            originalCommand = original,
//            normalizedCommand = normalized,
//            parsedIntent = parsed.first,
//            parsedTarget = parsed.second
//        )
//    }
//
//    fun normalizeCommand(command: String): String {
//        var text = command
//            .trim()
//            .replace(Regex("\\s+"), " ")
//
//        val replacements = listOf(
//            "open up" to "open",
//            "go to" to "open",
//            "look for" to "search for",
//            "look up" to "search for",
//            "find" to "search for",
//            "write" to "type",
//            "enter" to "type",
//            "click" to "tap",
//            "press" to "tap",
//            "go back" to "back",
//            "go home" to "home",
//            "cancel" to "stop"
//        )
//
//        for ((from, to) in replacements) {
//            text = text.replace(
//                Regex("(?i)\\b${Regex.escape(from)}\\b"),
//                to
//            )
//        }
//
//        return text.trim()
//    }
//
//    private data class IntentSpec(
//        val intent: String,
//        val triggerWords: Set<String>,
//        val fillerWords: Set<String>
//    )
//
//    private val intentSpecs = listOf(
//        IntentSpec(
//            intent = "search",
//            triggerWords = setOf("search"),
//            fillerWords = setOf(
//                "search",
//                "for",
//                "please",
//                "the"
//            )
//        ),
//
//        IntentSpec(
//            intent = "type",
//            triggerWords = setOf("type"),
//            fillerWords = setOf(
//                "type",
//                "please",
//                "the"
//            )
//        ),
//
//        IntentSpec(
//            intent = "open_chat",
//            triggerWords = setOf("chat"),
//            fillerWords = setOf(
//                "open",
//                "my",
//                "the",
//                "please",
//                "with",
//                "to",
//                "up",
//                "a",
//                "chat"
//            )
//        ),
//
//        IntentSpec(
//            intent = "tap",
//            triggerWords = setOf("tap"),
//            fillerWords = setOf(
//                "tap",
//                "on",
//                "the",
//                "please"
//            )
//        )
//    )
//
//    fun parseNormalizedCommand(command: String): Pair<String, String> {
//        val lower = command.lowercase().trim()
//        val tokens = lower
//            .split(Regex("\\s+"))
//            .filter { it.isNotBlank() }
//
//        when {
//            lower.contains("scroll down") ||
//                    lower.contains("swipe down") ->
//                return "scroll" to "down"
//
//            lower.contains("scroll up") ||
//                    lower.contains("swipe up") ->
//                return "scroll" to "up"
//
//            lower.contains("scroll left") ||
//                    lower.contains("swipe left") ->
//                return "scroll" to "left"
//
//            lower.contains("scroll right") ||
//                    lower.contains("swipe right") ->
//                return "scroll" to "right"
//
//            lower == "back" ->
//                return "back" to ""
//
//            lower == "home" ->
//                return "home" to ""
//
//            lower == "stop" ->
//                return "stop" to ""
//        }
//
//        Regex("^(?:video call|video chat) (.+)$")
//            .find(lower)
//            ?.let { match ->
//                val contact = match.groupValues[1].trim()
//                if (contact.isNotBlank()) {
//                    return "video_call" to contact
//                }
//            }
//
//        Regex("^call (.+)$")
//            .find(lower)
//            ?.let { match ->
//                val contact = match.groupValues[1].trim()
//                if (contact.isNotBlank()) {
//                    return "call" to contact
//                }
//            }
//
//        Regex("^type (.+?) (?:and )send(?: it)?$")
//            .find(lower)
//            ?.let { match ->
//                val message = match.groupValues[1].trim()
//                if (message.isNotBlank()) {
//                    return "type_and_send" to message
//                }
//            }
//
//        Regex("^send(?: (?:him|her|it))? (.+)$")
//            .find(lower)
//            ?.let { match ->
//                val message = match.groupValues[1].trim()
//                if (message.isNotBlank()) {
//                    return "type_and_send" to message
//                }
//            }
//
//        for (spec in intentSpecs) {
//            if (tokens.any { it in spec.triggerWords }) {
//                val target = tokens
//                    .filterNot { it in spec.fillerWords }
//                    .joinToString(" ")
//                    .trim()
//
//                if (target.isNotBlank()) {
//                    return spec.intent to target
//                }
//            }
//        }
//
//        return when {
//            lower.startsWith("open ") ->
//                "open" to lower.removePrefix("open ").trim()
//
//            lower.startsWith("message ") ->
//                "message" to lower.removePrefix("message ").trim()
//
//            lower.startsWith("send ") ->
//                "type_and_send" to lower.removePrefix("send ").trim()
//
//            lower.startsWith("timer ") ||
//                    lower.startsWith("set a timer") ->
//                "timer" to lower
//
//            lower.contains("take a picture") ||
//                    lower.contains("take a photo") ||
//                    lower == "open camera" ->
//                "camera" to lower
//
//            lower.startsWith("calendar") ||
//                    lower.startsWith("create calendar") ||
//                    lower.startsWith("add calendar") ->
//                "calendar" to lower
//
//            else ->
//                "unknown" to command
//        }
//    }
//
//    fun message(
//        key: String,
//        language: String = "english",
//        extra: String = ""
//    ): String {
//        return when (key) {
//            "listening" -> "Listening..."
//            "processing_voice" -> "Processing voice..."
//            "understood" -> "Understood: $extra"
//            "checking" -> "Checking screen..."
//            "thinking" -> "Thinking..."
//            "doing" -> "Doing it..."
//            "done" -> "Done."
//            "error" -> "Something went wrong. Please try again."
//            "need_help" -> extra.ifBlank {
//                "I need help. What should I do?"
//            }
//            "local_model_failed" ->
//                "Local model failed. Please try again."
//
//            else -> extra.ifBlank { "Okay." }
//        }
//    }
//}




package com.example.ghostmachine

object VoiceLanguageManager {

    data class VoiceCommandContext(
        val originalCommand: String,
        val normalizedCommand: String,
        val parsedIntent: String,
        val parsedTarget: String,
        val replyLanguage: String = "english"
    )

    fun buildContext(command: String): VoiceCommandContext {
        val original = command.trim()
        val normalized = normalizeCommand(original)
        val parsed = parseNormalizedCommand(normalized)

        return VoiceCommandContext(
            originalCommand = original,
            normalizedCommand = normalized,
            parsedIntent = parsed.first,
            parsedTarget = parsed.second
        )
    }

    fun normalizeCommand(command: String): String {
        var text = command.trim().replace(Regex("\\s+"), " ")

        val replacements = listOf(
            "open up" to "open",
            "go to" to "open",
            "look for" to "search for",
            "look up" to "search for",
            "find" to "search for",
            "write" to "type",
            "enter" to "type",
            "click" to "tap",
            "press" to "tap",
            "go back" to "back",
            "go home" to "home",
            "cancel" to "stop"
        )

        for ((from, to) in replacements) {
            text = text.replace(
                Regex("(?i)\\b${Regex.escape(from)}\\b"),
                to
            )
        }

        return text.trim()
    }

    private data class IntentSpec(
        val intent: String,
        val triggerWords: Set<String>,
        val fillerWords: Set<String>
    )

    private val intentSpecs = listOf(
        IntentSpec(
            intent = "search",
            triggerWords = setOf("search"),
            fillerWords = setOf("search", "for", "please", "the")
        ),
        IntentSpec(
            intent = "type",
            triggerWords = setOf("type"),
            fillerWords = setOf("type", "please", "the")
        ),
        IntentSpec(
            intent = "open_chat",
            triggerWords = setOf("chat"),
            fillerWords = setOf("open", "my", "the", "please", "with", "to", "up", "a", "chat")
        ),
        IntentSpec(
            intent = "tap",
            triggerWords = setOf("tap"),
            fillerWords = setOf("tap", "on", "the", "please")
        )
    )

    fun parseNormalizedCommand(command: String): Pair<String, String> {
        val lower = command.lowercase().trim()

        // Clean tokens by removing punctuation attached to words
        val tokens = lower
            .split(Regex("[\\s\\p{Punct}]+"))
            .filter { it.isNotBlank() }

        // 1. Navigation / Direct Actions
        when {
            lower.contains("scroll down") || lower.contains("swipe down") -> return "scroll" to "down"
            lower.contains("scroll up") || lower.contains("swipe up") -> return "scroll" to "up"
            lower.contains("scroll left") || lower.contains("swipe left") -> return "scroll" to "left"
            lower.contains("scroll right") || lower.contains("swipe right") -> return "scroll" to "right"
            lower == "back" -> return "back" to ""
            lower == "home" -> return "home" to ""
            lower == "stop" -> return "stop" to ""
        }

        // 2. Specific Patterns (Regex)
        Regex("^(?:video call|video chat) (.+)$").find(lower)?.let { match ->
            val contact = match.groupValues[1].trim()
            if (contact.isNotBlank()) return "video_call" to contact
        }

        Regex("^call (.+)$").find(lower)?.let { match ->
            val contact = match.groupValues[1].trim()
            if (contact.isNotBlank()) return "call" to contact
        }

        Regex("^type (.+?) (?:and )send(?: it)?$").find(lower)?.let { match ->
            val message = match.groupValues[1].trim()
            if (message.isNotBlank()) return "type_and_send" to message
        }

        Regex("^send(?: (?:him|her|it))? (.+)$").find(lower)?.let { match ->
            val message = match.groupValues[1].trim()
            if (message.isNotBlank()) return "type_and_send" to message
        }

        // 3. Fallback Intent Specs
        for (spec in intentSpecs) {
            if (tokens.any { it in spec.triggerWords }) {
                val target = tokens
                    .filterNot { it in spec.fillerWords }
                    .joinToString(" ")
                    .trim()

                if (target.isNotBlank()) {
                    return spec.intent to target
                }
            }
        }

        // 4. General Fallbacks
        return when {
            lower.startsWith("open ") -> "open" to lower.removePrefix("open ").trim()
            lower.startsWith("message ") -> "message" to lower.removePrefix("message ").trim()
            lower.startsWith("timer ") || lower.startsWith("set a timer") -> "timer" to lower
            lower.contains("take a picture") || lower.contains("take a photo") || lower == "open camera" -> "camera" to lower
            lower.startsWith("calendar") || lower.startsWith("create calendar") || lower.startsWith("add calendar") -> "calendar" to lower
            else -> "unknown" to command
        }
    }

    fun message(
        key: String,
        language: String = "english",
        extra: String = ""
    ): String {
        return when (key) {
            "listening" -> "Listening..."
            "processing_voice" -> "Processing voice..."
            "understood" -> "Understood: $extra"
            "checking" -> "Checking screen..."
            "thinking" -> "Thinking..."
            "doing" -> "Doing it..."
            "done" -> "Done."
            "error" -> "Something went wrong. Please try again."
            "need_help" -> extra.ifBlank { "I need help. What should I do?" }
            "local_model_failed" -> "Local model failed. Please try again."
            else -> extra.ifBlank { "Okay." }
        }
    }
}