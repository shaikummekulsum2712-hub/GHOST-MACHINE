package com.example.ghostmachine

object VoiceLanguageManager {

    data class VoiceCommandContext(
        val originalCommand: String,
        val normalizedCommand: String,
        val parsedIntent: String,
        val parsedTarget: String,
        val replyLanguage: String,
        val parsedPerson: String = "",
        val parsedMessage: String = "",
        val parsedApp: String = "",
        val durationSeconds: Long = 0L
    )

    private data class Parsed(
        val intent: String,
        val target: String = "",
        val person: String = "",
        val message: String = "",
        val app: String = "",
        val durationSeconds: Long = 0L
    )

    private val teluguWords = setOf(
        "cheyyi", "chesey", "chesi", "pettu", "kanipistundi", "pampu",
        "vaddu", "aapu", "sare", "rayi"
    )

    private val hindiHinglishWords = setOf(
        "karo", "kar", "kholo", "bhejo", "mat", "ruk", "ruko", "haan",
        "nahi", "dhundo", "khojo", "likho"
    )

    private fun tokenize(command: String): List<String> =
        Regex("[a-zA-Z0-9]+").findAll(command.lowercase()).map { it.value }.toList()

    fun buildContext(command: String): VoiceCommandContext {
        val original = command.trim()
        val language = detectReplyLanguage(original)
        val normalized = normalizeCommand(original)
        val parsed = parse(normalized)

        return VoiceCommandContext(
            originalCommand = original,
            normalizedCommand = normalized,
            parsedIntent = parsed.intent,
            parsedTarget = parsed.target,
            replyLanguage = language,
            parsedPerson = parsed.person,
            parsedMessage = parsed.message,
            parsedApp = parsed.app,
            durationSeconds = parsed.durationSeconds
        )
    }

    fun detectReplyLanguage(command: String): String {
        val tokens = tokenize(command)
        if (tokens.isEmpty()) return "english"
        val set = tokens.toSet()
        val teluguScore = set.count { it in teluguWords }
        val hinglishScore = set.count { it in hindiHinglishWords }
        return when {
            teluguScore == 0 && hinglishScore == 0 -> "english"
            teluguScore > hinglishScore -> "telugu"
            else -> "hinglish"
        }
    }

    fun normalizeCommand(command: String): String {
        var text = command.lowercase().trim()

        val phraseReplacements = listOf(
            "search karo" to "search for", "search kar" to "search for",
            "dhundo" to "search for", "khojo" to "search for",
            "open karo" to "open", "kholo" to "open",
            "bhejo" to "send", "likho" to "type",
            "search cheyyi" to "search for", "search chesey" to "search for",
            "open cheyyi" to "open", "open chesey" to "open",
            "type cheyyi" to "type", "ruk jao" to "stop", "ruko" to "stop"
        )
        for ((from, to) in phraseReplacements) text = text.replace(from, to)

        val wordReplacements = listOf("rayi" to "type", "pampu" to "send", "aapu" to "stop")
        for ((from, to) in wordReplacements) {
            text = text.replace(Regex("\\b${Regex.escape(from)}\\b"), to)
        }

        return text.replace(Regex("\\s+"), " ").trim()
    }

    // Kept for any legacy caller that still wants the simple (intent, target) shape.
    fun parseNormalizedCommand(command: String): Pair<String, String> {
        val p = parse(command)
        return p.intent to p.target
    }

    private val appNamePattern =
        "(whatsapp business|whatsapp|instagram|telegram|facebook messenger|messenger|facebook|messages|sms|phone|dialer|chrome|google|youtube)"

    private fun parse(command: String): Parsed {
        val lower = command.lowercase().trim()
        if (lower.isBlank()) return Parsed("unknown", "")

        when {
            lower == "stop" || lower == "cancel" -> return Parsed("stop")
            lower == "home" || lower == "go home" || lower == "home screen" -> return Parsed("home")
            lower == "back" || lower == "go back" -> return Parsed("back")
            lower.contains("scroll down") || lower.contains("swipe down") -> return Parsed("scroll", "down")
            lower.contains("scroll up") || lower.contains("swipe up") -> return Parsed("scroll", "up")
            lower.contains("scroll left") || lower.contains("swipe left") -> return Parsed("scroll", "left")
            lower.contains("scroll right") || lower.contains("swipe right") -> return Parsed("scroll", "right")
        }

        parseTimer(lower)?.let { return it }
        parseVideoCall(lower)?.let { return it }
        parseCall(lower)?.let { return it }
        parseSendOnly(lower)?.let { return it }
        parseTypeAndSend(lower)?.let { return it }
        parseOpenChat(lower)?.let { return it }
        parseType(lower)?.let { return it }
        parseSearch(lower)?.let { return it }
        parseOpen(lower)?.let { return it }
        parseTap(lower)?.let { return it }

        return Parsed("unknown", command)
    }

    private fun parseTimer(lower: String): Parsed? {
        if (!lower.contains("timer")) return null
        val match = Regex(
            "(?:set|start|create|make|put)?\\s*(?:me\\s*)?(?:a\\s*)?timer\\s*(?:for\\s*)?(\\d+(?:\\.\\d+)?)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)"
        ).find(lower) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2]
        val seconds = when {
            unit.startsWith("hour") || unit.startsWith("hr") -> (value * 3600.0).toLong()
            unit.startsWith("minute") || unit.startsWith("min") -> (value * 60.0).toLong()
            else -> value.toLong()
        }
        if (seconds <= 0L) return null
        return Parsed("timer", seconds.toString(), durationSeconds = seconds)
    }

    // "send it" / "send" (no message text) - send whatever is ALREADY typed.
    private fun parseSendOnly(lower: String): Parsed? {
        if (lower == "send" || lower == "send it" || lower == "send this" || lower == "send that") {
            return Parsed("send")
        }
        return null
    }

    // "type hello and send it" / "type hello and send" / "send hello" with real
    // content / "msg/message/reply hello" -> type (if needed) then send.
    private fun parseTypeAndSend(lower: String): Parsed? {
        val typeAndSend = Regex("^type\\s+(.+?)\\s+(?:and\\s+)?send(?:\\s+it)?$").find(lower)
        if (typeAndSend != null) {
            val message = typeAndSend.groupValues[1].trim()
            return Parsed("type_and_send", message, message = message)
        }

        val contextMessage = Regex(
            "^(?:send|msg|message|text|reply|tell (?:him|her|them))\\s+(?:him\\s+|her\\s+|them\\s+|it\\s+)?(.+)$"
        ).find(lower)
        if (contextMessage != null) {
            val message = contextMessage.groupValues[1].trim()
            if (message.isNotBlank()) return Parsed("type_and_send", message, message = message)
        }
        return null
    }

    private fun parseVideoCall(lower: String): Parsed? {
        val match = Regex(
            "^(?:video call|video chat)(?:\\s+to)?\\s+(.+?)(?:\\s+(?:on|via|using|in|from)\\s+$appNamePattern)?$"
        ).find(lower) ?: return null
        val person = match.groupValues[1].trim()
        val app = match.groupValues.getOrNull(2)?.trim().orEmpty()
        if (person.isBlank()) return null
        return Parsed("video_call", person, person = person, app = app)
    }

    private fun parseCall(lower: String): Parsed? {
        val match = Regex(
            "^(?:call|phone|ring)(?:\\s+to)?\\s+(.+?)(?:\\s+(?:on|via|using|in|from)\\s+$appNamePattern)?$"
        ).find(lower) ?: return null
        val person = match.groupValues[1].trim()
        val app = match.groupValues.getOrNull(2)?.trim().orEmpty()
        if (person.isBlank()) return null
        return Parsed("call", person, person = person, app = app)
    }

    // "open chat with X [in/from/on App]" / "message X [in/from/on App]" /
    // "open chat [in App]" (no person)
    private fun parseOpenChat(lower: String): Parsed? {
        val withPerson = Regex(
            "^(?:open\\s+)?(?:chat|message|messages|messaging|conversation)\\s+(?:with\\s+|to\\s+)?(.+?)(?:\\s+(?:on|via|using|in|from)\\s+$appNamePattern)?$"
        ).find(lower)
        if (withPerson != null) {
            val person = withPerson.groupValues[1].trim()
            val app = withPerson.groupValues.getOrNull(2)?.trim().orEmpty()
            if (person.isNotBlank() && person !in setOf("with", "to")) {
                return Parsed("open_chat", person, person = person, app = app)
            }
        }

        val noPerson = Regex(
            "^(?:open|start|go to)\\s+(?:chat|message|messages|messaging|conversation)(?:\\s+(?:on|via|using|in|from)\\s+$appNamePattern)?$"
        ).find(lower)
        if (noPerson != null) {
            val app = noPerson.groupValues.getOrNull(1)?.trim().orEmpty()
            return Parsed("open_chat", "", person = "", app = app)
        }

        if (lower == "open chat" || lower == "chat" || lower == "messages" || lower == "open messages") {
            return Parsed("open_chat")
        }
        return null
    }

    private fun parseType(lower: String): Parsed? {
        val match = Regex("^(?:type|write|enter)\\s+(.+)$").find(lower) ?: return null
        return Parsed("type", match.groupValues[1].trim())
    }

    private fun parseSearch(lower: String): Parsed? {
        val match = Regex(
            "^(?:search|find|look(?:\\s+for)?)(?:\\s+for)?\\s+(.+?)(?:\\s+(?:in|on)\\s+$appNamePattern)?$"
        ).find(lower) ?: return null
        val query = match.groupValues[1].trim()
        val app = match.groupValues.getOrNull(2)?.trim().orEmpty()
        if (query.isBlank()) return null
        return Parsed("search", query, app = app)
    }

    private fun parseOpen(lower: String): Parsed? {
        val match = Regex("^(?:open|launch|start)\\s+(.+)$").find(lower) ?: return null
        val target = match.groupValues[1].trim()
        if (target.isBlank()) return null
        return Parsed("open", target)
    }

    private fun parseTap(lower: String): Parsed? {
        val match = Regex("^(?:tap|click|press|select)\\s+(?:on\\s+)?(.+)$").find(lower) ?: return null
        return Parsed("tap", match.groupValues[1].trim())
    }

    fun message(key: String, language: String, extra: String = ""): String = when (language) {
        "hinglish" -> hinglishMessage(key, extra)
        "telugu" -> teluguMessage(key, extra)
        else -> englishMessage(key, extra)
    }

    private fun englishMessage(key: String, extra: String): String = when (key) {
        "heard" -> "You said: $extra"
        "listening" -> "Listening..."
        "processing_voice" -> "Processing voice..."
        "understood" -> "Understood: $extra"
        "checking" -> "Checking screen..."
        "thinking" -> "Thinking..."
        "doing" -> "Doing it..."
        "done" -> "Done."
        "error" -> "Something went wrong. Please try again."
        "need_help" -> extra.ifBlank { "I need help. What should I do?" }
        else -> extra.ifBlank { "Okay." }
    }

    private fun hinglishMessage(key: String, extra: String): String = when (key) {
        "heard" -> "Aapne kaha: $extra"
        "listening" -> "Sun raha hoon..."
        "processing_voice" -> "Voice process kar raha hoon..."
        "understood" -> "Samajh gaya: $extra"
        "checking" -> "Screen check kar raha hoon..."
        "thinking" -> "Soch raha hoon..."
        "doing" -> "Kar raha hoon..."
        "done" -> "Ho gaya."
        "error" -> "Kuch galat ho gaya. Phir try karo."
        "need_help" -> extra.ifBlank { "Mujhe help chahiye. Kya karu?" }
        else -> extra.ifBlank { "Theek hai." }
    }

    private fun teluguMessage(key: String, extra: String): String = when (key) {
        "heard" -> "Meeru cheppindi: $extra"
        "listening" -> "Vintunnanu..."
        "processing_voice" -> "Voice process chesthunnanu..."
        "understood" -> "Ardham ayyindi: $extra"
        "checking" -> "Screen check chesthunnanu..."
        "thinking" -> "Alochistunnanu..."
        "doing" -> "Chesthunnanu..."
        "done" -> "Ayyindi."
        "error" -> "Edo tappu ayyindi. Malli try cheyyi."
        "need_help" -> extra.ifBlank { "Naaku help kavali. Emi cheyyali?" }
        else -> extra.ifBlank { "Sare." }
    }
}