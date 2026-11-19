package com.nihira.assistant

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Turns an [Action] into something real on the phone.
 *
 * Two steps, so approval always shows the REAL target:
 *   1. prepare()  – resolve app / contact, returns an error or null if ready
 *   2. describe() – human sentence for the approval question
 *   3. execute()  – actually do it, returns what NIHIRA should say
 */
class ActionExecutor(private val ctx: Context) {

    // ---------------- 1. prepare ----------------

    fun prepare(a: Action): String? = when (a.name) {
        "open_app" -> {
            val app = findApp(a.str("app_name"))
            if (app == null) "I couldn't find an app called ${a.str("app_name")}."
            else { a.args.put("_label", app.first).put("_package", app.second); null }
        }
        "make_call", "send_message" -> {
            val c = findContact(a.str("contact"))
            if (c == null) {
                if (!has(Manifest.permission.READ_CONTACTS)) "I need contacts permission to find ${a.str("contact")}."
                else "I couldn't find ${a.str("contact")} in your contacts."
            } else { a.args.put("_name", c.first).put("_number", c.second); null }
        }
        else -> if (a.name in KNOWN) null else "I don't know how to ${a.name.replace('_', ' ')} yet."
    }

    // ---------------- 2. describe ----------------

    fun describe(a: Action): String = when (a.name) {
        "open_app" -> "open ${a.str("_label")}"
        "make_call" -> "call ${a.str("_name")}, number ending ${a.str("_number").takeLast(4)}"
        "send_message" -> {
            val via = if (a.str("app") == "sms") "SMS" else "WhatsApp"
            "send ${a.str("_name")} this $via message: ${a.str("message")}"
        }
        "set_alarm" -> "set an alarm for ${clock(a.int("hour"), a.int("minute"))}"
        "set_timer" -> "start a timer for ${duration(a.int("seconds"))}"
        "navigate" -> "start navigation to ${a.str("destination")}"
        "web_search" -> "search the web for ${a.str("query")}"
        "play_youtube" -> "play ${a.str("query")} on YouTube"
        "play_music" -> "play ${a.str("query")}"
        "flashlight" -> "turn the flashlight ${if (a.bool("on", true)) "on" else "off"}"
        "set_volume" -> "set the volume to ${a.int("percent")} percent"
        "open_settings" -> "open ${a.str("page")} settings"
        else -> a.name.replace('_', ' ')
    }

    // ---------------- 3. execute ----------------

    fun execute(a: Action): String = try {
        when (a.name) {
            "open_app" -> openApp(a)
            "make_call" -> call(a.str("_number"), a.str("_name"))
            "send_message" -> message(a)
            "set_alarm" -> setAlarm(a)
            "set_timer" -> setTimer(a)
            "navigate" -> navigate(a.str("destination"))
            "web_search" -> webSearch(a.str("query"))
            "play_youtube" -> youtube(a.str("query"))
            "play_music" -> music(a.str("query"))
            "flashlight" -> flashlight(a.bool("on", true))
            "set_volume" -> volume(a.int("percent"))
            "open_settings" -> settings(a.str("page"))
            else -> "I can't do that yet."
        }
    } catch (e: Exception) {
        "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
    }

    // ---------------- action implementations ----------------

    private fun openApp(a: Action): String {
        val intent = ctx.packageManager.getLaunchIntentForPackage(a.str("_package"))
            ?: return "I couldn't open ${a.str("_label")}."
        start(intent)
        return "Opening ${a.str("_label")}."
    }

    private fun call(number: String, name: String): String {
        val action = if (has(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        start(Intent(action, Uri.parse("tel:" + Uri.encode(number))))
        return if (action == Intent.ACTION_CALL) "Calling $name." else "I've opened the dialer for $name."
    }

    private fun message(a: Action): String {
        val number = a.str("_number")
        val text = a.str("message")
        val name = a.str("_name")
        if (a.str("app") == "sms") {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
                .putExtra("sms_body", text)
            start(i)
            return "I've written the SMS to $name. Tap send to deliver it."
        }
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=${waNumber(number)}&text=${Uri.encode(text)}")
        val opened = tryStart(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp")) ||
            tryStart(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp.w4b")) ||
            tryStart(Intent(Intent.ACTION_VIEW, uri))
        // Phase 4 (Accessibility Service) will press the send button automatically.
        return if (opened) "I've opened WhatsApp with your message to $name. Tap send to deliver it."
        else "WhatsApp doesn't seem to be installed."
    }

    private fun setAlarm(a: Action): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, a.int("hour").coerceIn(0, 23))
            .putExtra(AlarmClock.EXTRA_MINUTES, a.int("minute").coerceIn(0, 59))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (a.str("label").isNotEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, a.str("label"))
        start(i)
        return "Alarm set for ${clock(a.int("hour"), a.int("minute"))}."
    }

    private fun setTimer(a: Action): String {
        val secs = a.int("seconds").coerceAtLeast(1)
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (a.str("label").isNotEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, a.str("label"))
        start(i)
        return "Timer started for ${duration(secs)}."
    }

    private fun navigate(dest: String): String {
        val ok = tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest)))
            .setPackage("com.google.android.apps.maps")) ||
            tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))))
        return if (ok) "Starting navigation to $dest." else "I couldn't open maps."
    }

    private fun webSearch(q: String): String {
        val ok = tryStart(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)) ||
            tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        return if (ok) "Searching for $q." else "I couldn't open a browser."
    }

    private fun youtube(q: String): String {
        val ok = tryStart(Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q)) ||
            tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
        return if (ok) "Searching YouTube for $q." else "I couldn't open YouTube."
    }

    private fun music(q: String): String {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, q)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        return if (tryStart(i)) "Playing $q." else youtube(q)
    }

    private fun flashlight(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "This phone has no flashlight I can control."
        cm.setTorchMode(id, on)
        return if (on) "Flashlight on." else "Flashlight off."
    }

    private fun volume(percent: Int): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val p = percent.coerceIn(0, 100)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (p * max + 50) / 100, AudioManager.FLAG_SHOW_UI)
        return "Volume set to $p percent."
    }

    private fun settings(page: String): String {
        val action = when (page) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        if (!tryStart(Intent(action))) start(Intent(Settings.ACTION_SETTINGS))
        return "Opening $page settings."
    }

    // ---------------- lookup helpers ----------------

    /** Fuzzy-matches a spoken app name to an installed app. Returns (label, package). */
    private fun findApp(spoken: String): Pair<String, String>? {
        val target = normalize(spoken)
        if (target.isEmpty()) return null
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0).map {
            it.loadLabel(pm).toString() to it.activityInfo.packageName
        }.distinctBy { it.second }

        var best: Pair<String, String>? = null
        var bestScore = Int.MAX_VALUE
        for (app in apps) {
            val label = normalize(app.first)
            val score = when {
                label == target -> 0
                label.startsWith(target) || target.startsWith(label) -> 1
                label.contains(target) -> 2
                else -> 10 + levenshtein(label, target)
            }
            if (score < bestScore) { bestScore = score; best = app }
        }
        val tolerance = 10 + maxOf(1, target.length / 4)
        return if (bestScore <= 2 || bestScore <= tolerance) best else null
    }

    /** Finds a contact by name (or accepts a raw number). Returns (name, number). */
    private fun findContact(query: String): Pair<String, String>? {
        val digits = query.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 5) return query to digits
        if (query.isBlank() || !has(Manifest.permission.READ_CONTACTS)) return null

        ctx.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER),
            "${Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            null
        )?.use { c ->
            var first: Pair<String, String>? = null
            var startsWith: Pair<String, String>? = null
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                if (name.equals(query, ignoreCase = true)) return name to number
                if (startsWith == null && name.startsWith(query, ignoreCase = true)) startsWith = name to number
                if (first == null) first = name to number
            }
            return startsWith ?: first
        }
        return null
    }

    // ---------------- small utilities ----------------

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    private fun start(i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun tryStart(i: Intent): Boolean = try { start(i); true } catch (e: ActivityNotFoundException) { false }

    /** WhatsApp wants the number with country code and no '+', e.g. 919876543210. */
    private fun waNumber(n: String): String {
        var d = n.filter { it.isDigit() }
        if (d.length == 11 && d.startsWith("0")) d = d.drop(1)
        if (d.length == 10) d = "91$d"
        return d
    }

    private fun normalize(s: String) =
        s.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9]"), "")

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun clock(h: Int, m: Int): String {
        val hh = if (h % 12 == 0) 12 else h % 12
        return String.format(Locale.ENGLISH, "%d:%02d %s", hh, m, if (h < 12) "AM" else "PM")
    }

    private fun duration(s: Int) = when {
        s % 3600 == 0 -> "${s / 3600} hour${if (s / 3600 == 1) "" else "s"}"
        s % 60 == 0 -> "${s / 60} minute${if (s / 60 == 1) "" else "s"}"
        else -> "$s seconds"
    }

    companion object {
        private val KNOWN = setOf(
            "open_app", "make_call", "send_message", "set_alarm", "set_timer", "navigate",
            "web_search", "play_youtube", "play_music", "flashlight", "set_volume", "open_settings"
        )
    }
}
