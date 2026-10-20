package dev.viveknshah.payannouncer.intake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import dev.viveknshah.payannouncer.AppGraph
import java.util.concurrent.Executors

/**
 * Sideloaded `foss` flavor only: bank SMS arrives as a broadcast and goes
 * through exactly the same parser, ledger and speaker as a notification.
 *
 * The Play flavor does not declare `RECEIVE_SMS`; SMS permission is a
 * sideload-only decision, as the roadmap requires.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }.trim()
        if (body.isEmpty()) return

        val first = messages.first()
        val sender = first.displayOriginatingAddress
            ?: first.originatingAddress
            ?: "SMS"
        val atMillis = first.timestampMillis

        val pending = goAsync()
        worker.execute {
            try {
                val record = AppGraph.intake(context).ingest(sender, body, atMillis)
                if (record != null) AppGraph.speech(context).announce(record)
            } catch (e: Exception) {
                Log.e(TAG, "SMS intake failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SmsIntake"
        val worker = Executors.newSingleThreadExecutor()
    }
}
