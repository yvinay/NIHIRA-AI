package com.nihira.assistant

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** NIHIRA's "brain": turns what you said into phone actions or a spoken reply. */
interface Brain {
    data class Thought(val reply: String, val actions: List<Action>)

    val label: String
    suspend fun think(userText: String): Thought

    companion object {
        /** Shared instructions, used by both Gemini and Claude. */
        fun systemPrompt(): String {
            val now = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date())
            return """
                You are NIHIRA, a voice assistant that controls the user's Android phone, like Jarvis.
                The user lives in India and may mix English with Hindi or Kannada words (e.g. "Amma ko call karo").
                Current date and time: $now.

                Rules:
                - If the user wants something done on the phone, call the matching function(s). Do not write any text alongside function calls.
                - Call several functions in one reply if the user asked for several things.
                - Pass contact names exactly as the user said them. Never invent phone numbers.
                - Convert times to 24-hour format for alarms ("7 in the evening" = hour 19).
                - If the request is unclear or no function fits, reply with ONE short question or say what you can't do yet.
                - For general questions (facts, maths, advice), answer in 1–2 short spoken sentences.
                - Your text is read aloud: no markdown, no lists, no emojis.
            """.trimIndent()
        }

        /** Picks Gemini if a Gemini key is set, else Claude, else null (Phase 1 basic mode). */
        fun create(): Brain? = when {
            BuildConfig.GEMINI_API_KEY.isNotBlank() ->
                GeminiBrain(BuildConfig.GEMINI_API_KEY, BuildConfig.GEMINI_MODEL)
            BuildConfig.ANTHROPIC_API_KEY.isNotBlank() ->
                ClaudeBrain(BuildConfig.ANTHROPIC_API_KEY, BuildConfig.CLAUDE_MODEL)
            else -> null
        }
    }
}
