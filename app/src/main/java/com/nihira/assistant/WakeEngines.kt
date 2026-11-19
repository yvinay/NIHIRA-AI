package com.nihira.assistant

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import android.content.res.AssetManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.Locale

/** Something that listens for "Hi Nihira". start() also resumes after pause(). */
interface WakeEngine {
    val name: String
    fun start()
    fun pause()
    fun release()
}

// ---------------------------------------------------------------------------
// Picovoice Porcupine: best accuracy and battery. Needs PICOVOICE_ACCESS_KEY.
// ---------------------------------------------------------------------------
class PorcupineWakeEngine(private val ctx: Context, private val onWake: () -> Unit) : WakeEngine {
    override val name = "Picovoice"
    private var manager: PorcupineManager? = null

    override fun start() {
        if (manager == null) {
            manager = PorcupineManager.Builder()
                .setAccessKey(BuildConfig.PICOVOICE_ACCESS_KEY)
                .setKeywordPaths(arrayOf(keywordPath()))
                .setSensitivity(0.6f)
                .build(ctx.applicationContext) { _ -> onWake() }
        }
        manager?.start()
    }

    override fun pause() { runCatching { manager?.stop() } }

    override fun release() {
        runCatching { manager?.stop() }
        runCatching { manager?.delete() }
        manager = null
    }

    /** Uses assets/hi_nihira.ppn if present, else trains the phrase once (needs internet) and saves it. */
    private fun keywordPath(): String {
        if (ctx.assets.list("")?.contains("hi_nihira.ppn") == true) return "hi_nihira.ppn"
        val f = File(ctx.filesDir, "hi_nihira.ppn")
        if (!f.exists() || f.length() == 0L) {
            Porcupine.trainWakeWordFromPhrase(
                BuildConfig.PICOVOICE_ACCESS_KEY, f.absolutePath, "en", WakeWordService.PHRASE)
        }
        return f.absolutePath
    }
}

// ---------------------------------------------------------------------------
// Vosk: free, open-source, fully offline, no key. Uses a little more battery.
// "Nihira" isn't an English word, so we accept close-sounding versions.
// ---------------------------------------------------------------------------
class VoskWakeEngine(private val ctx: Context, private val onWake: () -> Unit) : WakeEngine {
    override val name = "Vosk (offline)"
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null

    private var startedAt = 0L

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = inspect(hypothesis, "partial")
        override fun onResult(hypothesis: String?) = inspect(hypothesis, "text")
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) {}
        override fun onTimeout() {}
    }

    override fun start() {
        if (model == null) model = Model(installModel(ctx).absolutePath)
        if (speech == null) {
            // A fresh recognizer each time, so nothing heard before the pause can re-trigger.
            recognizer = Recognizer(model, SAMPLE_RATE)
            speech = SpeechService(recognizer, SAMPLE_RATE)
        }
        startedAt = System.currentTimeMillis()
        speech?.startListening(listener)
    }

    /** Stops recording, which frees the microphone for NIHIRA's speech recognition. */
    override fun pause() {
        runCatching { speech?.stop() }
        runCatching { speech?.shutdown() }
        runCatching { recognizer?.close() }
        speech = null
        recognizer = null
    }

    override fun release() {
        pause()
        runCatching { model?.close() }
        model = null
    }

    private fun inspect(json: String?, key: String) {
        if (System.currentTimeMillis() - startedAt < 1500) return
        val text = runCatching { JSONObject(json ?: "{}").optString(key) }.getOrDefault("")
        if (text.isNotBlank() && WakePhrase.matches(text)) {
            pause()
            onWake()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16000f
        private const val ASSET_DIR = "vosk-model"

        fun hasModel(ctx: Context) = ctx.assets.list(ASSET_DIR)?.isNotEmpty() == true

        /** Copies the model from the APK to phone storage once (Vosk needs real files). */
        private fun installModel(ctx: Context): File {
            val dst = File(ctx.filesDir, ASSET_DIR)
            val marker = File(dst, ".installed-${BuildConfig.VERSION_CODE}")
            if (!marker.exists()) {
                dst.deleteRecursively()
                copyAssets(ctx.assets, ASSET_DIR, dst)
                marker.createNewFile()
            }
            return dst
        }

        private fun copyAssets(am: AssetManager, src: String, dst: File) {
            val children = am.list(src).orEmpty()
            if (children.isEmpty()) {
                dst.parentFile?.mkdirs()
                am.open(src).use { input -> dst.outputStream().use { input.copyTo(it) } }
            } else {
                dst.mkdirs()
                children.forEach { copyAssets(am, "$src/$it", File(dst, it)) }
            }
        }
    }
}

/** Decides whether recognised text sounds like "Hi Nihira". */
object WakePhrase {
    private val greetings = setOf("hi", "hey", "hai", "hay")
    private val nameSounds = listOf(
        "nihira", "nihara", "neehira", "nehira", "nihera", "neera", "nira", "nehra",
        "nearer", "neara", "nearah", "neerah", "nihar", "niara", "nyra", "nirah", "nehara", "neehara"
    )

    fun matches(raw: String): Boolean {
        val words = raw.lowercase(Locale.ENGLISH).split(Regex("[^a-z]+")).filter { it.isNotBlank() }
        for (i in words.indices) {
            if (words[i] !in greetings) continue
            // Join the next one or two words: "near a" -> "neara", "nee hira" -> "neehira"
            val one = words.getOrNull(i + 1) ?: continue
            val two = one + (words.getOrNull(i + 2) ?: "")
            if (sounds(one) || sounds(two)) return true
        }
        return false
    }

    private fun sounds(s: String): Boolean =
        s.length in 4..9 && nameSounds.any { it == s || (it.length >= 6 && s.length >= 5 && distance(it, s) <= 1) }

    private fun distance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val t = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return dp[b.length]
    }
}
