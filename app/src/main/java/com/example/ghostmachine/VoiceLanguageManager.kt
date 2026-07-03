package com.example.ghostmachine

object VoiceLanguageManager {

    data class VoiceCommandContext(
        val originalCommand: String,
        val normalizedCommand: String,
        val parsedIntent: String,
        val parsedTarget: String,
        val replyLanguage: String
    )

    // Whole-word dictionaries. Using Sets instead of Lists gives O(1) lookup
    // and, combined with tokenize(), guarantees we only match real standalone
    // words - not substrings buried inside unrelated words.
    private val teluguWords = setOf(
        "cheyyi", "chesey", "chesi", "pettu",
        "kanipistundi", "pampu", "vaddu", "aapu", "sare", "rayi"
    )

    private val hindiHinglishWords = setOf(
        "karo", "kar", "kholo", "bhejo", "mat", "ruk", "ruko",
        "haan", "nahi", "dhundo", "khojo", "likho"
    )

    /**
     * Splits a command into lowercase word tokens, stripping punctuation.
     * This is the basis for all word-boundary matching below, so we never
     * accidentally match a dictionary word that's just a substring of some
     * unrelated bigger word (e.g. "kar" inside "market").
     */
    private fun tokenize(command: String): List<String> {
        return Regex("[a-zA-Z]+")
            .findAll(command.lowercase())
            .map { it.value }
            .toList()
    }

    fun buildContext(command: String): VoiceCommandContext {
        val original = command.trim()
        val language = detectReplyLanguage(original)
        val normalized = normalizeCommand(original)
        val parsed = parseNormalizedCommand(normalized)

        return VoiceCommandContext(
            originalCommand = original,
            normalizedCommand = normalized,
            parsedIntent = parsed.first,
            parsedTarget = parsed.second,
            replyLanguage = language
        )
    }

    /**
     * Scores the command against each language's dictionary by counting how
     * many *distinct whole words* matched (not substrings), then returns the
     * language with the highest score. Ties, and cases with zero matches,
     * fall back to English so we never confidently guess wrong on thin
     * evidence.
     */
    fun detectReplyLanguage(command: String): String {
        val tokens = tokenize(command)
        if (tokens.isEmpty()) return "english"

        val tokenSet = tokens.toSet()

        val teluguScore = tokenSet.count { it in teluguWords }
        val hinglishScore = tokenSet.count { it in hindiHinglishWords }

        return when {
            teluguScore == 0 && hinglishScore == 0 -> "english"
            teluguScore > hinglishScore -> "telugu"
            hinglishScore > teluguScore -> "hinglish"
            // Tie with at least one match on both sides - ambiguous code-mix.
            // Default to hinglish since it's the larger dictionary/user base,
            // but this is a judgment call you can flip if needed.
            else -> "hinglish"
        }
    }

    /**
     * Replaces known multi-word phrases and single roman words with their
     * English equivalents. Multi-word phrases are naturally safe with plain
     * substring replace since spaces act as boundaries. Single-word
     * replacements use word-boundary regex so we don't corrupt unrelated
     * English words that happen to contain the same letters.
     */
    fun normalizeCommand(command: String): String {
        var text = command.lowercase().trim()

        // Multi-word phrases (space-delimited, so substring replace is safe here)
        val phraseReplacements = listOf(
            "search karo" to "search for",
            "search kar" to "search for",
            "dhundo" to "search for",
            "khojo" to "search for",
            "open karo" to "open",
            "kholo" to "open",
            "bhejo" to "send",
            "likho" to "type",
            "ruk jao" to "stop",
            "ruko" to "stop",
            "search cheyyi" to "search for",
            "search chesey" to "search for",
            "open cheyyi" to "open",
            "open chesey" to "open",
            "type cheyyi" to "type"
        )

        for ((from, to) in phraseReplacements) {
            text = text.replace(from, to)
        }

        // Single-word replacements - word-boundary matched so e.g. "rayi"
        // never matches as part of a longer unrelated word.
        val wordReplacements = listOf(
            "rayi" to "type",
            "pampu" to "send",
            "aapu" to "stop"
        )

        for ((from, to) in wordReplacements) {
            text = text.replace(Regex("\\b${Regex.escape(from)}\\b"), to)
        }

        // Common natural patterns: trailing "search"-style suffixes get
        // moved to the front as "search for X"
        if (text.endsWith(" search for")) {
            text = "search for " + text.removeSuffix(" search for").trim()
        } else if (text.endsWith(" search")) {
            text = "search for " + text.removeSuffix(" search").trim()
        } else if (text.endsWith(" search karo")) {
            text = "search for " + text.removeSuffix(" search karo").trim()
        } else if (text.endsWith(" search cheyyi")) {
            text = "search for " + text.removeSuffix(" search cheyyi").trim()
        }

        return text.trim()
    }

    private data class IntentSpec(
        val intent: String,
        val triggerWords: Set<String>,
        val fillerWords: Set<String>
    )

    // To support a new way of phrasing a command, add words to these sets.
// Do NOT add new regex patterns/prefixes elsewhere - this is the one place.
    private val intentSpecs = listOf(
        IntentSpec(
            intent = "search",
            triggerWords = setOf("search", "find", "look"),
            fillerWords = setOf("search", "for", "find", "look", "up", "please", "the")
        ),
        IntentSpec(
            intent = "type",
            triggerWords = setOf("type", "write", "enter"),
            fillerWords = setOf("type", "write", "enter", "please", "the")
        ),
        IntentSpec(
            intent = "open_chat",
            triggerWords = setOf("chat", "message", "text"),
            fillerWords = setOf("open", "my", "the", "please", "with", "to", "up", "a", "chat", "message", "text")
        ),
        IntentSpec(
            intent = "tap",
            triggerWords = setOf("tap", "click", "press", "select"),
            fillerWords = setOf("tap", "click", "press", "select", "on", "the", "please")
        )
    )

    fun parseNormalizedCommand(command: String): Pair<String, String> {
        val lower = command.lowercase().trim()
        val tokens = lower.split(Regex("\\s+")).filter { it.isNotBlank() }

        // Fixed-phrase intents that don't take a free-text target - keep these
        // as plain checks since there's nothing to extract.
        when {
            lower.contains("scroll down") || lower.contains("swipe down") -> return "scroll" to "down"
            lower.contains("scroll up") || lower.contains("swipe up") -> return "scroll" to "up"
            lower.contains("go back") || lower == "back" -> return "back" to ""
            lower == "home" -> return "home" to ""
            lower == "stop" || lower == "cancel" -> return "stop" to ""
        }

        // Trigger-word intents, checked in priority order. First one whose
        // trigger word appears anywhere in the command wins.
        for (spec in intentSpecs) {
            if (tokens.any { it in spec.triggerWords }) {
                val target = tokens.filterNot { it in spec.fillerWords }
                    .joinToString(" ")
                    .trim()

                if (target.isNotBlank()) {
                    return spec.intent to target
                }
            }
        }

        return "unknown" to command
    }

    fun message(key: String, language: String, extra: String = ""): String {
        return when (language) {
            "hinglish" -> hinglishMessage(key, extra)
            "telugu" -> teluguMessage(key, extra)
            else -> englishMessage(key, extra)
        }
    }

    private fun englishMessage(key: String, extra: String): String {
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

    private fun hinglishMessage(key: String, extra: String): String {
        return when (key) {
            "listening" -> "Sun raha hoon..."
            "processing_voice" -> "Voice process kar raha hoon..."
            "understood" -> "Samajh gaya: $extra"
            "checking" -> "Screen check kar raha hoon..."
            "thinking" -> "Soch raha hoon..."
            "doing" -> "Kar raha hoon..."
            "done" -> "Ho gaya."
            "error" -> "Kuch galat ho gaya. Phir try karo."
            "need_help" -> extra.ifBlank { "Mujhe help chahiye. Kya karu?" }
            "local_model_failed" -> "Local model fail ho gaya. Phir try karo."
            else -> extra.ifBlank { "Theek hai." }
        }
    }

    private fun teluguMessage(key: String, extra: String): String {
        return when (key) {
            "listening" -> "Vintunnanu..."
            "processing_voice" -> "Voice process chesthunnanu..."
            "understood" -> "Ardham ayyindi: $extra"
            "checking" -> "Screen check chesthunnanu..."
            "thinking" -> "Alochistunnanu..."
            "doing" -> "Chesthunnanu..."
            "done" -> "Ayyindi."
            "error" -> "Edo tappu ayyindi. Malli try cheyyi."
            "need_help" -> extra.ifBlank { "Naaku help kavali. Emi cheyyali?" }
            "local_model_failed" -> "Local model fail ayyindi. Malli try cheyyi."
            else -> extra.ifBlank { "Sare." }
        }
    }
}