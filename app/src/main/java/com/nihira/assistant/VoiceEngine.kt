package com.nihira.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID

/**
 * Ears (Android SpeechRecognizer) and mouth (Android TextToSpeech) of NIHIRA.
 * All callbacks are delivered on the main thread.
 */
class VoiceEngine(
    private val context: Context,
    private val onStatus: (String) -> Unit,
) : TextToSpeech.OnInitListener {

    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(context, this)
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private val pendingDone = mutableMapOf<String, () -> Unit>()
    private val prefs = context.getSharedPreferences("nihira", Context.MODE_PRIVATE)
    private var voiceChoices: List<Voice> = emptyList()

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val r = tts.setLanguage(Locale("en", "IN"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US)
        }
        // NIHIRA is female: choose a female voice (or the one you picked last time)
        voiceChoices = buildVoiceChoices()
        val saved = prefs.getString("voice", null)
        (voiceChoices.firstOrNull { it.name == saved } ?: voiceChoices.firstOrNull())
            ?.let { runCatching { tts.voice = it } }
        tts.setPitch(1.08f)      // a slightly brighter, friendlier tone
        tts.setSpeechRate(1.0f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = finish(id)
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = finish(id)
            override fun onStop(id: String?, interrupted: Boolean) = finish(id)
        })
        ttsReady = true
    }

    private fun finish(id: String?) {
        main.post { id?.let { pendingDone.remove(it)?.invoke() } }
    }

    /**
     * Android doesn't label voices as male/female, so we rank them:
     * Indian English first, then voices known to be female, offline before online.
     * Known male voices go to the end. The voice button lets you try the others.
     */
    private fun buildVoiceChoices(): List<Voice> {
        val all = runCatching { tts.voices?.toList() }.getOrNull().orEmpty()
            .filter { it.locale.language == "en" }
            .filterNot { (it.features ?: emptySet()).contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        val indian = all.filter { it.locale.country == "IN" }
        val pool = indian + all.filter { it.locale.country != "IN" && isLikelyFemale(it) }
        return pool.sortedBy { v ->
            var score = 0
            if (v.locale.country != "IN") score += 100
            if (!isLikelyFemale(v)) score += 50
            if (isLikelyMale(v)) score += 200
            if (v.isNetworkConnectionRequired) score += 5
            score
        }
    }

    private fun isLikelyFemale(v: Voice): Boolean {
        val n = v.name.lowercase(Locale.ENGLISH)
        return "female" in n || FEMALE_VOICES.any { n.startsWith(it) }
    }

    private fun isLikelyMale(v: Voice): Boolean {
        val n = v.name.lowercase(Locale.ENGLISH)
        return ("male" in n && "female" !in n) || MALE_VOICES.any { n.startsWith(it) }
    }

    /** Switches to the next voice, remembers it, and returns a short description. */
    fun nextVoice(): String? {
        if (voiceChoices.isEmpty()) return null
        val i = voiceChoices.indexOfFirst { it.name == tts.voice?.name }
        val v = voiceChoices[(i + 1) % voiceChoices.size]
        runCatching { tts.voice = v }
        prefs.edit().putString("voice", v.name).apply()
        return "Voice ${voiceChoices.indexOf(v) + 1} of ${voiceChoices.size} (${v.locale.displayCountry})"
    }

    /** Speaks [text]; [onDone] runs when speaking has finished. */
    fun speak(text: String, onDone: () -> Unit = {}) {
        if (!ttsReady || text.isBlank()) { main.post(onDone); return }
        val id = UUID.randomUUID().toString()
        pendingDone[id] = onDone
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    fun stopSpeaking() {
        tts.stop()
        val callbacks = pendingDone.values.toList()
        pendingDone.clear()
        callbacks.forEach { main.post(it) }
    }

    /** Listens once. Calls [onHeard] with the text, or [onFail] with a reason. */
    fun listen(onHeard: (String) -> Unit, onFail: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onFail("Speech recognition isn't available. Please install or update the Google app.")
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onStatus("Listening…")
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = onStatus("Processing…")
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) = onFail(describeError(error))

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isBlank()) onFail("I didn't catch that.") else onHeard(text)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        r.startListening(intent)
    }

    fun stopListening() {
        recognizer?.cancel()
    }

    fun shutdown() {
        recognizer?.destroy()
        recognizer = null
        tts.stop()
        tts.shutdown()
    }

    companion object {
        // Google TTS voice ids that are female / male (used only for ranking).
        private val FEMALE_VOICES = listOf(
            "en-in-x-ahp", "en-in-x-cxx", "en-in-x-ena",
            "en-us-x-tpf", "en-us-x-sfg", "en-us-x-iob", "en-gb-x-gba", "en-gb-x-gbc", "en-au-x-afa"
        )
        private val MALE_VOICES = listOf(
            "en-in-x-end", "en-us-x-tpd", "en-us-x-iom", "en-us-x-iol", "en-gb-x-gbd", "en-gb-x-rjs", "en-au-x-aud"
        )
    }

    private fun describeError(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network problem while listening."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "I need microphone permission."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy. Try again."
        SpeechRecognizer.ERROR_CLIENT -> "Listening was cancelled."
        else -> "Speech error ($code)."
    }
}
