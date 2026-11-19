package com.nihira.assistant

import android.Manifest
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var voice: VoiceEngine
    private lateinit var executor: ActionExecutor
    private var brain: Brain? = null

    private lateinit var orb: ImageView
    private lateinit var statusText: TextView
    private lateinit var chat: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var micButton: ImageButton
    private lateinit var approvalCard: LinearLayout
    private lateinit var approvalText: TextView
    private lateinit var alwaysAsk: MaterialSwitch
    private lateinit var typeInput: EditText
    private lateinit var wakeSwitch: MaterialSwitch
    private var wokenSession = false

    private var currentJob: Job? = null
    private var pendingApproval: CompletableDeferred<Boolean>? = null
    private var pulse: ValueAnimator? = null

    private enum class Who { ME, NIHIRA, INFO }
    private enum class Mood(val drawable: Int) {
        IDLE(R.drawable.orb_idle), LISTEN(R.drawable.orb_listen),
        THINK(R.drawable.orb_think), WAIT(R.drawable.orb_wait)
    }

    private val prefs by lazy { getSharedPreferences("nihira", MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys.map { it.substringAfterLast('.') }
            if (denied.isNotEmpty()) bubble(Who.INFO, "Not allowed: ${denied.joinToString()}. Some features won't work.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        orb = findViewById(R.id.orb)
        statusText = findViewById(R.id.statusText)
        chat = findViewById(R.id.chat)
        chatScroll = findViewById(R.id.chatScroll)
        micButton = findViewById(R.id.micButton)
        approvalCard = findViewById(R.id.approvalCard)
        approvalText = findViewById(R.id.approvalText)
        alwaysAsk = findViewById(R.id.alwaysAskSwitch)
        typeInput = findViewById(R.id.typeInput)
        wakeSwitch = findViewById(R.id.wakeSwitch)

        voice = VoiceEngine(this) { status(it) }
        executor = ActionExecutor(this)

        brain = Brain.create()
        bubble(Who.INFO, brain?.let { "Smart mode · ${it.label}" }
            ?: "Basic mode · try \"open YouTube\", \"call Amma\", \"set alarm for 6:30 am\"")
        bubble(Who.NIHIRA, "Hi! I'm Nihira. Tap the mic and tell me what to do.")

        alwaysAsk.isChecked = prefs.getBoolean("always_ask", true)
        alwaysAsk.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("always_ask", checked).apply()
        }

        micButton.setOnClickListener { onMicTapped() }
        findViewById<View>(R.id.approveButton).setOnClickListener { pendingApproval?.complete(true) }
        findViewById<View>(R.id.rejectButton).setOnClickListener { pendingApproval?.complete(false) }
        findViewById<View>(R.id.sendButton).setOnClickListener { submitTyped() }
        findViewById<View>(R.id.voiceButton).setOnClickListener { tryNextVoice() }
        typeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { submitTyped(); true } else false
        }

        wakeSwitch.isChecked = prefs.getBoolean("wake_word", false)
        wakeSwitch.setOnCheckedChangeListener { _, on -> setWakeWord(on) }

        askPermissions()
        handleWakeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWakeIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // e.g. after a reboot: switch the listener back on when you open NIHIRA
        if (prefs.getBoolean("wake_word", false) && !WakeWordService.running &&
            granted(Manifest.permission.RECORD_AUDIO)) {
            runCatching { WakeWordService.start(this) }
        }
    }

    // ---------------- Phase 3: "Hi Nihira" ----------------

    private fun setWakeWord(on: Boolean) {
        if (on && BuildConfig.PICOVOICE_ACCESS_KEY.isBlank() && !VoskWakeEngine.hasModel(this)) {
            bubble(Who.INFO, "This build has no wake-word engine. Rebuild with the latest workflow file.")
            wakeSwitch.isChecked = false
            return
        }
        prefs.edit().putBoolean("wake_word", on).apply()
        if (!on) {
            WakeWordService.stop(this)
            bubble(Who.INFO, "Wake word off")
            return
        }
        askPermissions()
        // Lets NIHIRA pop up over the lock screen when she hears you.
        if (!Settings.canDrawOverlays(this)) {
            bubble(Who.INFO, "Please allow “Display over other apps” for NIHIRA, then come back.")
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        // Stops Android / OnePlus battery saver from killing the listener.
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            WakeWordService.start(this)
            bubble(Who.INFO, "Wake word on · say “Hi Nihira” anytime")
        }
    }

    private fun handleWakeIntent(i: Intent?) {
        if (i == null || !i.getBooleanExtra(WakeWordService.EXTRA_WOKEN, false)) return
        i.removeExtra(WakeWordService.EXTRA_WOKEN)
        getSystemService(NotificationManager::class.java).cancel(WakeWordService.ALERT_ID)
        showOverLockScreen(true)
        if (currentJob?.isActive == true) return
        wokenSession = true
        currentJob = lifecycleScope.launch {
            busy()
            say("Yes?")
            val heard = hear()
            if (heard == null) say("I didn't catch that.") else handle(heard)
            idle()
        }
    }

    private fun showOverLockScreen(show: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(show)
            setTurnScreenOn(show)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (show) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    /** Asks for fingerprint / face / PIN. Android never lets an app unlock the phone by itself. */
    private suspend fun ensureUnlocked(): Boolean {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) return true
        say("Please unlock with your face or fingerprint.")
        return suspendCancellableCoroutine { cont ->
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { if (cont.isActive) cont.resume(true) }
                override fun onDismissCancelled() { if (cont.isActive) cont.resume(false) }
                override fun onDismissError() { if (cont.isActive) cont.resume(false) }
            })
        }
    }

    override fun onDestroy() {
        pulse?.cancel()
        voice.shutdown()
        super.onDestroy()
    }

    // ---------------- entry points ----------------

    private fun onMicTapped() {
        if (currentJob?.isActive == true) {          // tap again = stop everything
            currentJob?.cancel()
            pendingApproval?.complete(false)
            voice.stopListening()
            voice.stopSpeaking()
            idle()
            return
        }
        if (!granted(Manifest.permission.RECORD_AUDIO)) { askPermissions(); return }
        currentJob = lifecycleScope.launch {
            busy()
            val heard = hear()
            if (heard == null) say("I didn't catch that.") else handle(heard)
            idle()
        }
    }

    private fun submitTyped() {
        val text = typeInput.text.toString().trim()
        if (text.isEmpty() || currentJob?.isActive == true) return
        typeInput.setText("")
        currentJob = lifecycleScope.launch {
            busy()
            handle(text)
            idle()
        }
    }

    private fun tryNextVoice() {
        if (currentJob?.isActive == true) return
        val desc = voice.nextVoice()
        if (desc == null) {
            bubble(Who.INFO, "No other voices found. Install more in Settings › System › Languages › Text-to-speech.")
            return
        }
        bubble(Who.INFO, desc)
        voice.speak(getString(R.string.voice_sample))
    }

    // ---------------- the main loop: understand → approve → act → reply ----------------

    private suspend fun handle(text: String) {
        bubble(Who.ME, text)
        status("Thinking…")

        var reply = ""
        var actions: List<Action> = emptyList()
        val b = brain
        if (b != null) {
            try {
                val thought = b.think(text)
                reply = thought.reply
                actions = thought.actions
            } catch (e: Exception) {
                bubble(Who.INFO, e.message ?: "Couldn't reach the brain.")
                CommandRouter.route(text)?.let { actions = listOf(it) }
                if (actions.isEmpty()) reply =
                    if ((e.message ?: "").contains("busy")) "Gemini is busy right now. Please try again in a minute."
                    else "I couldn't reach my brain. Please check the internet connection."
            }
        } else {
            CommandRouter.route(text)?.let { actions = listOf(it) }
            if (actions.isEmpty()) reply = "Sorry, I don't know that command yet."
        }

        if (actions.isEmpty()) {
            say(reply.ifBlank { "Sorry, I'm not sure how to help with that." })
            return
        }

        // Locked phone: anything beyond flashlight/volume needs your face or fingerprint first.
        if (actions.any { Tools.needsUnlock(it) } && !ensureUnlocked()) {
            actions = actions.filterNot { Tools.needsUnlock(it) }
            say("Okay, I'll keep the phone locked.")
            if (actions.isEmpty()) return
        }

        // Approve everything first, while NIHIRA is still on screen
        // (once another app opens, Android blocks our microphone).
        val approved = mutableListOf<Action>()
        for (action in actions) {
            val problem = executor.prepare(action)
            if (problem != null) { say(problem); continue }

            val what = executor.describe(action)
            val needsApproval = alwaysAsk.isChecked || Tools.riskOf(action) == Risk.HIGH
            if (needsApproval && !askApproval("Shall I $what?")) {
                say("Okay, I won't $what.")
                continue
            }
            approved += action
        }

        for (action in approved) {
            say(executor.execute(action))
        }
    }

    // ---------------- approval: voice OR the 3D buttons, whichever comes first ----------------

    private suspend fun askApproval(question: String): Boolean {
        val decision = CompletableDeferred<Boolean>()
        pendingApproval = decision
        approvalText.text = question
        approvalCard.visibility = View.VISIBLE
        status("Waiting for your approval…")

        val voiceJob = lifecycleScope.launch {
            say(question)
            repeat(2) { attempt ->
                if (decision.isCompleted) return@launch
                when (Approval.parse(hear())) {
                    true -> { decision.complete(true); return@launch }
                    false -> { decision.complete(false); return@launch }
                    null -> if (attempt == 0 && !decision.isCompleted) say("Please say yes or no.")
                }
            }
            if (!decision.isCompleted) {
                say("I'll wait. Tap approve or cancel.")
                status("Waiting for your approval…")
            }
        }

        // Safety: no answer within 30 seconds counts as "no".
        val approved = withTimeoutOrNull(30_000) { decision.await() } ?: false

        voiceJob.cancel()
        voice.stopListening()
        approvalCard.visibility = View.GONE
        pendingApproval = null
        bubble(Who.INFO, if (approved) "Approved" else "Not approved")
        return approved
    }

    // ---------------- coroutine wrappers around the voice engine ----------------

    private suspend fun say(text: String) {
        bubble(Who.NIHIRA, text)
        suspendCancellableCoroutine<Unit> { cont ->
            voice.speak(text) { if (cont.isActive) cont.resume(Unit) }
            cont.invokeOnCancellation { voice.stopSpeaking() }
        }
    }

    private suspend fun hear(): String? {
        if (WakeWordService.running) {          // free the microphone for speech recognition
            WakeWordService.pause(this)
            delay(300)
        }
        return listenOnce()
    }

    private suspend fun listenOnce(): String? = suspendCancellableCoroutine { cont ->
        voice.listen(
            onHeard = { if (cont.isActive) cont.resume(it) },
            onFail = { reason ->
                if (reason != "Listening was cancelled.") bubble(Who.INFO, reason)
                if (cont.isActive) cont.resume(null)
            }
        )
        cont.invokeOnCancellation { voice.stopListening() }
    }

    // ---------------- UI helpers ----------------

    private fun busy() {
        micButton.setImageResource(R.drawable.btn_stop_3d)
        micButton.contentDescription = "Stop"
    }

    private fun idle() {
        micButton.setImageResource(R.drawable.btn_mic_3d)
        micButton.contentDescription = getString(R.string.tap_to_speak)
        approvalCard.visibility = View.GONE
        status("Ready")
        WakeWordService.resume(this)            // listen for "Hi Nihira" again
        if (wokenSession) {
            wokenSession = false
            showOverLockScreen(false)
            // Still locked (nothing needed unlocking)? Go back to the lock screen.
            if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) moveTaskToBack(true)
        }
    }

    /** Updates the status line and the orb's colour: lilac idle, mint listening, blue thinking, peach waiting. */
    private fun status(s: String) {
        statusText.text = s
        val mood = when {
            s.startsWith("Listening") -> Mood.LISTEN
            s.startsWith("Processing") || s.startsWith("Thinking") -> Mood.THINK
            s.startsWith("Waiting") -> Mood.WAIT
            else -> Mood.IDLE
        }
        orb.setImageResource(mood.drawable)
        if (mood == Mood.LISTEN || mood == Mood.THINK) startPulse() else stopPulse()
    }

    private fun startPulse() {
        if (pulse?.isRunning == true) return
        pulse = ValueAnimator.ofFloat(1f, 1.08f).apply {
            duration = 650
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { val v = it.animatedValue as Float; orb.scaleX = v; orb.scaleY = v }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        orb.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Adds a chat bubble: you on the right (blue), NIHIRA on the left (white, with her orb). */
    private fun bubble(who: Who, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(getColor(if (who == Who.INFO) R.color.ink_soft else R.color.ink))
            textSize = if (who == Who.INFO) 12f else 15f
            setLineSpacing(0f, 1.15f)
            maxWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()
            if (who != Who.INFO) {
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setBackgroundResource(if (who == Who.ME) R.drawable.bubble_user else R.drawable.bubble_nihira)
            } else {
                gravity = Gravity.CENTER
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = when (who) {
                Who.ME -> Gravity.END
                Who.NIHIRA -> Gravity.START or Gravity.BOTTOM
                Who.INFO -> Gravity.CENTER_HORIZONTAL
            }
            setPadding(0, dp(4), 0, dp(4))
        }
        if (who == Who.NIHIRA) {
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.orb_idle)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(6) })
        }
        row.addView(tv)
        chat.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val needed = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
        ).let { if (Build.VERSION.SDK_INT >= 33) it + Manifest.permission.POST_NOTIFICATIONS else it }
            .filterNot { granted(it) }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }
}
