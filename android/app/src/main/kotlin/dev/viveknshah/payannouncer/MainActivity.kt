package dev.viveknshah.payannouncer

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.viveknshah.payannouncer.config.ConfigLoader
import dev.viveknshah.payannouncer.intake.NotificationIntakeService
import dev.viveknshah.payannouncer.ledger.Ledger
import dev.viveknshah.payannouncer.parser.Parser
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Spike UI: show whether notification access is on, today's ledger totals, and
 * the latest parsed records. Voice and the summary card come next.
 */
class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var accessText: TextView
    private lateinit var configText: TextView
    private lateinit var statsText: TextView
    private lateinit var recentText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(24))
        }

        root.addView(label("Payment Announcer", 26f, bold = true))
        root.addView(
            label(
                "Listens for payment notifications and keeps a local ledger. " +
                    "No internet permission.",
                15f,
            ),
        )

        accessText = label("", 17f, bold = true)
        root.addView(accessText)

        val settingsButton = Button(this).apply {
            text = "Open notification access settings"
            setOnClickListener { openNotificationAccess() }
        }
        root.addView(settingsButton, fullWidth())

        configText = label("", 13f)
        root.addView(configText)

        root.addView(label("Today", 18f, bold = true))
        statsText = label("", 16f)
        root.addView(statsText)

        root.addView(label("Latest records", 18f, bold = true))
        recentText = label("", 14f)
        root.addView(recentText)

        root.addView(
            label(
                "Only GPay, PhonePe, Paytm and Messages notifications are read. " +
                    "Bank SMS notifications are best effort; the SMS build is later. " +
                    "Raw notification text is not stored.",
                13f,
            ),
        )

        setContentView(root)
        configText.text = loadConfigStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshAccess()
        refreshLedger()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
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
            "Notification access: OFF\nTurn it on below, then ask someone to send a ₹1 test."
        }
    }

    private fun refreshLedger() {
        io.execute {
            val result = try {
                val ledger = Ledger(AppGraph.store(applicationContext))
                val stats = ledger.dailyStats(LocalDate.now())
                val latest = ledger.allRecords().takeLast(5).asReversed()

                val summary = buildString {
                    if (stats.count == 0) {
                        append("No payments yet today.")
                    } else {
                        append("${stats.count} payments · ${money(stats.totalPaise)}")
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
                        "$time  ${money(record.amountPaise)}  ${record.status}$who"
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

    private fun openNotificationAccess() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            accessText.text = "Could not open settings: ${e.message}"
        }
    }

    private fun loadConfigStatus(): String = try {
        ConfigLoader.load(assets.open("config.json"))
        "Config: loaded from assets"
    } catch (e: Exception) {
        "Config error: ${e.message}"
    }

    private fun label(value: String, sizeSp: Float, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setPadding(0, 0, 0, dp(8))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(12) }

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
}
