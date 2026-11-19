package com.nihira.assistant

import org.json.JSONArray
import org.json.JSONObject

/** One thing NIHIRA can do on the phone, e.g. open_app {app_name: "YouTube"}. */
data class Action(val name: String, val args: JSONObject) {
    fun str(key: String): String = args.optString(key, "").trim()
    fun int(key: String, def: Int = 0): Int = args.optInt(key, def)
    fun bool(key: String, def: Boolean = false): Boolean = args.optBoolean(key, def)
}

/**
 * LOW  = harmless (open an app, play music). Runs without asking,
 *        unless "Ask my approval before every action" is switched on.
 * HIGH = affects other people or costs something (calls, messages). ALWAYS asks.
 */
enum class Risk { LOW, HIGH }

object Tools {

    private val riskLevels = mapOf(
        "open_app" to Risk.LOW,
        "web_search" to Risk.LOW,
        "play_youtube" to Risk.LOW,
        "play_music" to Risk.LOW,
        "navigate" to Risk.LOW,
        "set_timer" to Risk.LOW,
        "set_alarm" to Risk.LOW,
        "flashlight" to Risk.LOW,
        "set_volume" to Risk.LOW,
        "open_settings" to Risk.LOW,
        "make_call" to Risk.HIGH,
        "send_message" to Risk.HIGH,
    )

    /** Actions that are safe to do while the phone is still locked. Everything else needs an unlock. */
    private val worksWhileLocked = setOf("flashlight", "set_volume")
    fun needsUnlock(a: Action): Boolean = a.name !in worksWhileLocked

    /** Unknown tools are treated as HIGH risk, so they always need approval. */
    fun riskOf(a: Action): Risk = riskLevels[a.name] ?: Risk.HIGH

    // ---------- Tool definitions sent to Claude (Phase 2 "brain") ----------

    private fun prop(type: String, desc: String, enum: List<String>? = null) =
        JSONObject().put("type", type).put("description", desc).apply {
            if (enum != null) put("enum", JSONArray(enum))
        }

    private fun tool(name: String, desc: String, props: Map<String, JSONObject>, required: List<String>) =
        JSONObject()
            .put("name", name)
            .put("description", desc)
            .put("input_schema", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
                .put("required", JSONArray(required)))

    val schema: JSONArray by lazy {
        JSONArray()
            .put(tool("open_app", "Open an installed app on the phone.",
                mapOf("app_name" to prop("string", "App name as the user said it, e.g. 'YouTube', 'Instagram', 'PhonePe'.")),
                listOf("app_name")))
            .put(tool("make_call", "Place a phone call to a contact or number.",
                mapOf("contact" to prop("string", "Contact name exactly as the user said it (e.g. 'Amma', 'Ravi'), or a phone number.")),
                listOf("contact")))
            .put(tool("send_message", "Send a text message to a contact via WhatsApp or SMS.",
                mapOf(
                    "contact" to prop("string", "Contact name as said by the user, or a phone number."),
                    "message" to prop("string", "The message text to send, written naturally in first person from the user."),
                    "app" to prop("string", "Which app to use. Default whatsapp.", listOf("whatsapp", "sms"))),
                listOf("contact", "message")))
            .put(tool("set_alarm", "Set an alarm in the clock app.",
                mapOf(
                    "hour" to prop("integer", "Hour in 24-hour format, 0-23."),
                    "minute" to prop("integer", "Minute, 0-59."),
                    "label" to prop("string", "Optional alarm label.")),
                listOf("hour", "minute")))
            .put(tool("set_timer", "Start a countdown timer.",
                mapOf(
                    "seconds" to prop("integer", "Timer length in seconds."),
                    "label" to prop("string", "Optional label.")),
                listOf("seconds")))
            .put(tool("navigate", "Start Google Maps navigation to a place.",
                mapOf("destination" to prop("string", "Place or address.")),
                listOf("destination")))
            .put(tool("web_search", "Search the web on the phone's browser. Use when the user explicitly asks to search or look something up.",
                mapOf("query" to prop("string", "Search query.")),
                listOf("query")))
            .put(tool("play_youtube", "Search and play something on YouTube.",
                mapOf("query" to prop("string", "What to search on YouTube.")),
                listOf("query")))
            .put(tool("play_music", "Play a song, artist or playlist in the user's music app.",
                mapOf("query" to prop("string", "Song / artist / playlist.")),
                listOf("query")))
            .put(tool("flashlight", "Turn the flashlight (torch) on or off.",
                mapOf("on" to prop("boolean", "true = on, false = off.")),
                listOf("on")))
            .put(tool("set_volume", "Set the media volume.",
                mapOf("percent" to prop("integer", "Volume level 0-100.")),
                listOf("percent")))
            .put(tool("open_settings", "Open a phone settings page.",
                mapOf("page" to prop("string", "Which settings page.",
                    listOf("main", "wifi", "bluetooth", "display", "sound", "battery", "location", "apps"))),
                listOf("page")))
    }
}
