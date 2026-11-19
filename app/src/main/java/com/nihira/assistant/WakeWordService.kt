package com.nihira.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Phase 3: listens for "Hi Nihira" in the background (even when the phone is locked).
 * Uses Picovoice if a PICOVOICE_ACCESS_KEY was built in, otherwise the free offline Vosk engine.
 * Both run fully on the phone.
 * On detection it wakes the screen and opens NIHIRA, which then listens for your command.
 */
class WakeWordService : Service() {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var engine: WakeEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground("Starting…")
        when (intent?.action) {
            ACTION_PAUSE -> pauseListening()
            ACTION_STOP -> {
                pauseListening()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker.execute {
            engine?.release()
            engine = null
        }
        worker.shutdown()
        super.onDestroy()
    }

    private fun startInForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, ongoingNotification(text), type)
    }

    private fun update(text: String) = main.post {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, ongoingNotification(text))
    }

    private fun startListening() = worker.execute {
        try {
            if (engine == null) engine = createEngine()
            engine?.start()
            update("Say “$PHRASE” anytime · ${engine?.name}")
        } catch (e: Exception) {
            update("Wake word problem: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Picovoice when a key is present (falls back to Vosk if it fails), otherwise Vosk. */
    private fun createEngine(): WakeEngine {
        val onWake: () -> Unit = { main.post { onWakeWord() } }
        if (BuildConfig.PICOVOICE_ACCESS_KEY.isNotBlank()) {
            update("Preparing “$PHRASE” with Picovoice (first time needs internet)…")
            val pv = PorcupineWakeEngine(this, onWake)
            try {
                pv.start()
                pv.pause()
                return pv
            } catch (e: Exception) {
                pv.release()
                if (!VoskWakeEngine.hasModel(this)) throw e
                update("Picovoice failed (${e.message}); using offline Vosk")
            }
        }
        require(VoskWakeEngine.hasModel(this)) { "No offline voice model in this build." }
        update("Preparing offline “$PHRASE” (first time takes a few seconds)…")
        return VoskWakeEngine(this, onWake)
    }

    private fun pauseListening() = worker.execute {
        engine?.pause()
        update("Paused while NIHIRA is listening")
    }

    /** "Hi Nihira" heard: free the microphone, wake the screen and open NIHIRA. */
    private fun onWakeWord() {
        pauseListening()
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_WOKEN, true)
        // Works when "Display over other apps" is allowed for NIHIRA.
        runCatching { startActivity(open) }
        // Backup: a full-screen alert that also turns the screen on.
        val pi = PendingIntent.getActivity(
            this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alert = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_nihira)
            .setContentTitle("NIHIRA is listening")
            .setContentText("Go ahead, I'm listening.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(ALERT_ID, alert) }
    }

    private fun ongoingNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_nihira)
            .setContentTitle("NIHIRA")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        const val PHRASE = "Hi Nihira"
        const val EXTRA_WOKEN = "woken"
        const val ALERT_ID = 2
        private const val NOTIF_ID = 1
        private const val CHANNEL_ONGOING = "nihira_wake"
        private const val CHANNEL_ALERT = "nihira_alert"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_STOP = "stop"

        @Volatile var running = false
            private set

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, WakeWordService::class.java))

        fun pause(ctx: Context) = send(ctx, ACTION_PAUSE)
        fun resume(ctx: Context) = send(ctx, "resume")
        fun stop(ctx: Context) = send(ctx, ACTION_STOP)

        private fun send(ctx: Context, action: String) {
            if (!running) return
            runCatching { ctx.startService(Intent(ctx, WakeWordService::class.java).setAction(action)) }
        }

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ONGOING, "Wake word", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ALERT, "NIHIRA woke up", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}
