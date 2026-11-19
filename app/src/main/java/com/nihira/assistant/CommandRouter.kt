package com.nihira.assistant

import org.json.JSONObject

/**
 * Phase 1: simple offline keyword matching.
 * Used when there is no API key, no internet, or the Claude call fails.
 */
object CommandRouter {

    private fun act(name: String, vararg kv: Pair<String, Any>) =
        Action(name, JSONObject().apply { kv.forEach { put(it.first, it.second) } })

    fun route(raw: String): Action? {
        val t = raw.lowercase(java.util.Locale.ENGLISH)
            .replace(Regex("^(hey |ok |hi )?nihira[, ]*"), "")
            .replace(Regex("^(please |can you |could you )"), "")
            .trim(' ', '.', '?', '!')

        Regex("^(?:call|phone|dial) (.+)$").find(t)?.let {
            return act("make_call", "contact" to it.groupValues[1])
        }
        Regex("^play (.+) on youtube$").find(t)?.let {
            return act("play_youtube", "query" to it.groupValues[1])
        }
        Regex("^play (.+)$").find(t)?.let {
            return act("play_music", "query" to it.groupValues[1])
        }
        Regex("^(?:search|google|look up)(?: for)? (.+)$").find(t)?.let {
            return act("web_search", "query" to it.groupValues[1])
        }
        Regex("^(?:navigate|directions|take me|drive) to (.+)$").find(t)?.let {
            return act("navigate", "destination" to it.groupValues[1])
        }
        if (Regex("flash ?light|torch").containsMatchIn(t)) {
            return act("flashlight", "on" to !Regex("\\boff\\b").containsMatchIn(t))
        }
        Regex("timer for (\\d+) (second|minute|hour)").find(t)?.let {
            val n = it.groupValues[1].toInt()
            val mult = when (it.groupValues[2]) { "second" -> 1; "minute" -> 60; else -> 3600 }
            return act("set_timer", "seconds" to n * mult)
        }
        Regex("alarm (?:for|at) (\\d{1,2})(?:[: ](\\d{2}))? ?([ap])?\\.? ?m?\\.?").find(t)?.let {
            var h = it.groupValues[1].toInt()
            val m = it.groupValues[2].ifEmpty { "0" }.toInt()
            when (it.groupValues[3]) {
                "p" -> if (h < 12) h += 12
                "a" -> if (h == 12) h = 0
            }
            return act("set_alarm", "hour" to h, "minute" to m)
        }
        Regex("volume (?:to )?(\\d{1,3})").find(t)?.let {
            return act("set_volume", "percent" to it.groupValues[1].toInt().coerceIn(0, 100))
        }
        Regex("^(?:open|launch|start) (?:the )?(.+?)(?: app)?$").find(t)?.let {
            return act("open_app", "app_name" to it.groupValues[1])
        }
        when {
            "wifi" in t || "wi-fi" in t -> return act("open_settings", "page" to "wifi")
            "bluetooth" in t -> return act("open_settings", "page" to "bluetooth")
        }
        return null
    }
}

/** Understands spoken yes/no answers, including common Hindi/Kannada words. */
object Approval {
    private val noWords = setOf("no", "nope", "cancel", "stop", "dont", "don't", "nahi", "nahin", "na", "illa", "beda", "wait", "abort")
    private val yesWords = setOf("yes", "yeah", "yep", "yup", "ok", "okay", "sure", "confirm", "approve", "approved",
        "haan", "han", "haa", "ha", "ho", "houdu", "sari", "correct", "proceed", "send", "call", "go")

    /** true = approved, false = rejected, null = unclear. "No" always wins over "yes". */
    fun parse(text: String?): Boolean? {
        val s = text?.lowercase(java.util.Locale.ENGLISH) ?: return null
        val words = s.split(Regex("[^a-z']+")).filter { it.isNotBlank() }.toSet()
        if (words.any { it in noWords } || "do not" in s) return false
        if (words.any { it in yesWords } || "go ahead" in s || "do it" in s) return true
        return null
    }
}
