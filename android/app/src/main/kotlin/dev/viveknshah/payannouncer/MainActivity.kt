package dev.viveknshah.payannouncer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.viveknshah.payannouncer.config.ConfigLoader
import dev.viveknshah.payannouncer.intake.NotificationIntakeService
import dev.viveknshah.payannouncer.ledger.Ledger
import dev.viveknshah.payannouncer.parser.Parser
import dev.viveknshah.payannouncer.voice.HindiSummary
import dev.viveknshah.payannouncer.voice.HindiTtsEngine
import dev.viveknshah.payannouncer.watchdog.WatchdogService
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * One screen for the chai stall: grants, today's total, the Hindi day summary,
 * undo, and the last few records. Deliberately no Compose dependency.
 *
 * The UI copy is English; the spoken product is Hindi. There is no separate
 * English/Hindi UI resource set in this one-stall build, so lint's SetTextI18n
 * warnings are suppressed here rather than pretending the screen is localized.
 */
@SuppressLint("SetTextI18n")
class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val isFoss = BuildConfig.FLAVOR == "foss"

    private lateinit var accessText: TextView
    private lateinit var smsText: TextView
    private lateinit var smsButton: Button
    private lateinit var ttsText: TextView
    private lateinit var watchdogText: TextView
    private lateinit var watchdogButton: Button
    private lateinit var statsText: TextView
    private lateinit var summaryText: TextView
    private lateinit var recentText: TextView
    private lateinit var configText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }

        root.addView(label("Payment Announcer", 27f, bold = true))
        root.addView(
            label(
                "Speaks every counted payment in Hindi and keeps the day's books " +
                    "on this phone. No internet permission.",
                15f,
            ),
        )

        root.addView(section("1. Permissions"))
        accessText = label("", 16f, bold = true)
        root.addView(accessText)
        root.addView(button("Open notification access settings") { openNotificationAccess() })

        smsText = label("", 15f)
        root.addView(smsText)
        smsButton = button("Allow SMS access") { requestSmsPermission() }
        root.addView(smsButton)

        ttsText = label("", 15f, bold = true)
        root.addView(ttsText)
        root.addView(button("Test Hindi voice") { testVoice() })
        root.addView(button("Open text-to-speech settings") { openTtsSettings() })

        root.addView(
            label(
                "Battery: allow Payment Announcer to keep running so the listener " +
                    "is not killed in the background.",
                14f,
            ),
        )
        root.addView(button("Battery optimization settings") { openBatterySettings() })

        watchdogText = label("", 14f)
        root.addView(watchdogText)
        watchdogButton = button("Start background service") { startWatchdog() }
        root.addView(watchdogButton)

        root.addView(section("2. Today"))
        statsText = label("", 16f)
        root.addView(statsText)
        root.addView(button("Speak day summary") { speakDaySummary() })
        summaryText = label("Tap “Speak day summary” for the Hindi total.", 14f)
        root.addView(summaryText)
        root.addView(button("Undo last sale") { undoLastSale() })

        root.addView(section("3. Latest records"))
        recentText = label("", 14f)
        root.addView(recentText)

        configText = label("", 12f)
        root.addView(configText)
        root.addView(
            label(
                "Only a small allowlist of payment apps and known bank apps is read. " +
                    "The foss build also accepts bank SMS. Raw text is not stored. " +
                    "No money movement is possible from this app.",
                12f,
            ),
        )

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        configText.text = loadConfigStatus()
        maybeRequestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshAccess()
        refreshSms()
        refreshTts()
        refreshWatchdog()
        WatchdogService.startIfNeeded(this)
        refreshLedger()
        main.postDelayed({ if (!isFinishing && !isDestroyed) refreshWatchdog() }, 500)
        main.postDelayed({ if (!isFinishing && !isDestroyed) refreshTts() }, 1_200)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_SMS) {
            refreshSms()
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "SMS access granted", Toast.LENGTH_SHORT).show()
                WatchdogService.startIfNeeded(this)
                refreshWatchdog()
            } else {
                Toast.makeText(this, "SMS access is needed for the foss SMS build", Toast.LENGTH_LONG)
                    .show()
            }
        } else if (requestCode == REQUEST_NOTIFICATIONS) {
            WatchdogService.startIfNeeded(this)
            refreshWatchdog()
        }
    }

    private fun refreshAccess() {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        val me = ComponentName(this, NotificationIntakeService::class.java)
        val granted = enabled
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == me }

        accessText.text = if (granted) {
            "Notification access: ON"
        } else {
            "Notification access: OFF — turn it on, then ask someone to send a ₹1 test."
        }
    }

    private fun refreshSms() {
        if (!isFoss) {
            smsText.text = "SMS: not in this Play build. Sideload the foss APK for bank SMS."
            smsButton.visibility = View.GONE
            return
        }

        val granted = checkSelfPermission(Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
        smsText.text = if (granted) {
            "SMS: ON"
        } else {
            "SMS: OFF — allow SMS access for bank alerts."
        }
        smsButton.text = if (granted) "SMS access granted" else "Allow SMS access"
        smsButton.isEnabled = !granted
        smsButton.visibility = View.VISIBLE
    }

    private fun refreshTts() {
        val status = AppGraph.speech(this).status
        ttsText.text = when (status) {
            HindiTtsEngine.Status.INITIALIZING -> "Hindi voice: starting…"
            HindiTtsEngine.Status.READY -> "Hindi voice: ready"
            HindiTtsEngine.Status.HINDI_MISSING ->
                "Hindi voice: missing. Install a Hindi TTS voice in system settings."
            HindiTtsEngine.Status.ERROR -> "Hindi voice: unavailable. Open TTS settings."
        }
    }

    private fun refreshWatchdog() {
        watchdogText.text = if (WatchdogService.running) {
            "Background: on — the ongoing notification keeps the listener resident."
        } else {
            "Background: off. Grant access, then start the watchdog."
        }
        watchdogButton.text = if (WatchdogService.running) {
            "Background service is on"
        } else {
            "Start background service"
        }
        watchdogButton.isEnabled = !WatchdogService.running
    }

    private fun startWatchdog() {
        if (!WatchdogService.isNeeded(this)) {
            Toast.makeText(this, "Grant notification or SMS access first", Toast.LENGTH_SHORT).show()
            return
        }
        WatchdogService.startIfNeeded(this)
        main.postDelayed({ if (!isFinishing && !isDestroyed) refreshWatchdog() }, 500)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val prefs = getSharedPreferences("onboarding", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATIONS,
        )
    }

    private fun refreshLedger() {
        io.execute {
            val result = try {
                val ledger = AppGraph.ledger(applicationContext)
                val stats = ledger.dailyStats(LocalDate.now())
                val latest = ledger.allRecords().takeLast(8).asReversed()

                val summary = buildString {
                    if (stats.count == 0) {
                        append("No sales yet today.")
                    } else {
                        append("${stats.count} payments · ${money(stats.totalPaise)}")
                    }
                    if (stats.refundCount > 0) {
                        append("\nRefunds: ${stats.refundCount} · ${money(stats.refundTotalPaise)}")
                    }
                    if (stats.reviewCount > 0) append("\nNeeds review: ${stats.reviewCount}")
                    if (stats.duplicateCount > 0) {
                        append("\nDuplicates ignored: ${stats.duplicateCount}")
                    }
                }
                val recent = if (latest.isEmpty()) {
                    "No records yet."
                } else {
                    latest.joinToString("\n") { record ->
                        val time = record.timestamp.substringAfter('T').take(5)
                        val who = record.payer?.let { " · $it" }.orEmpty()
                        val flag = if (record.needsReview) " ⚠" else ""
                        "$time  ${money(record.amountPaise)}  ${record.status}$flag$who"
                    }
                }
                summary to recent
            } catch (e: Exception) {
                "Could not read the ledger: ${e.message}" to ""
            }

            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    statsText.text = result.first
                    recentText.text = result.second
                }
            }
        }
    }

    private fun speakDaySummary() {
        io.execute {
            val text = try {
                HindiSummary.text(AppGraph.ledger(applicationContext).dailyStats(LocalDate.now()))
            } catch (e: Exception) {
                "आज का हिसाब नहीं पढ़ा जा सका।"
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    summaryText.text = text
                    AppGraph.speech(this).speak(text)
                }
            }
        }
    }

    private fun undoLastSale() {
        io.execute {
            var error: Exception? = null
            val removed = try {
                AppGraph.ledger(applicationContext).undoLastSale()
            } catch (e: Exception) {
                error = e
                null
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    val message = when {
                        error != null -> "आखिरी बिक्री नहीं हटाई जा सकी।"
                        removed != null -> "आखिरी बिक्री हटा दी।"
                        else -> "हटाने के लिए कोई बिक्री नहीं।"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    AppGraph.speech(this).speak(message)
                    refreshLedger()
                }
            }
        }
    }

    private fun testVoice() {
        val engine = AppGraph.speech(this)
        if (engine.status == HindiTtsEngine.Status.READY) {
            engine.speakVoiceCheck()
        } else {
            Toast.makeText(this, "Hindi voice is not ready", Toast.LENGTH_SHORT).show()
            openTtsSettings()
        }
    }

    private fun requestSmsPermission() {
        if (!isFoss) return
        requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), REQUEST_SMS)
    }

    private fun openNotificationAccess() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            accessText.text = "Could not open settings: ${e.message}"
        }
    }

    private fun openTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (second: Exception) {
                ttsText.text = "Could not open TTS settings: ${second.message}"
            }
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                })
            } catch (second: Exception) {
                Toast.makeText(this, "Could not open battery settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadConfigStatus(): String = try {
        ConfigLoader.load(assets.open("config.json"))
        "Config: loaded · cross-source window 120 s"
    } catch (e: Exception) {
        "Config error: ${e.message}"
    }

    private fun section(value: String): TextView = label(value, 19f, bold = true).apply {
        setPadding(0, dp(22), 0, dp(8))
    }

    private fun label(value: String, sizeSp: Float, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setPadding(0, 0, 0, dp(8))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(value: String, onClick: () -> Unit) =
        Button(this).apply {
            text = value
            setOnClickListener { onClick() }
            layoutParams = fullWidth()
        }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(10) }

    private fun money(paise: Long): String {
        val rupees = paise / 100
        val fraction = (paise % 100).toInt()
        return if (fraction == 0) {
            "₹${Parser.formatRupees(paise)}"
        } else {
            "₹${Parser.formatRupees(rupees * 100)}.${fraction.toString().padStart(2, '0')}"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val REQUEST_SMS = 41
        const val REQUEST_NOTIFICATIONS = 42
    }
}
