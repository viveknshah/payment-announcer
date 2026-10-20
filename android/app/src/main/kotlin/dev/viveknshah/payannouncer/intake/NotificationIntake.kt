package dev.viveknshah.payannouncer.intake

import dev.viveknshah.payannouncer.ledger.Ledger
import dev.viveknshah.payannouncer.parser.Parser
import dev.viveknshah.payannouncer.parser.ParserConfig
import dev.viveknshah.payannouncer.parser.PaymentRecord
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Turns a notification into the same kind of record as an SMS.
 *
 * The package name is the sender stand-in: the parser's sender lists are keyed
 * on tokens such as GPAY, PHONEPE and BOB. Messages notifications use their
 * title, which is often the bank sender ("BOB", "HDFC Bank").
 *
 * This is deliberately a small allowlist. A notification from an unmapped app
 * is ignored, not guessed at.
 */
class NotificationIntake(
    private val ledger: Ledger,
    private val config: ParserConfig,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {

    fun ingest(snapshot: NotificationSnapshot): PaymentRecord? {
        if (snapshot.groupSummary) return null
        val sender = senderFor(snapshot) ?: return null
        val body = snapshot.body()
        if (body.isBlank()) return null

        val at = if (snapshot.postedAtMillis > 0) {
            Instant.ofEpochMilli(snapshot.postedAtMillis).atZone(zoneId).toLocalDateTime()
        } else {
            LocalDateTime.now(zoneId)
        }
        return ledger.add(Parser.parseSms(sender, body, config, at))
    }

    private fun senderFor(snapshot: NotificationSnapshot): String? {
        val mapped = PACKAGE_SENDERS[snapshot.packageName] ?: return null
        if (mapped != MESSAGES) return mapped
        return snapshot.title?.trim()?.takeIf(String::isNotEmpty) ?: MESSAGES
    }

    private companion object {
        const val MESSAGES = "MESSAGES"

        /**
         * Known package ids only. Bank apps vary by OEM and version, so add
         * them after seeing real notifications on a phone.
         */
        val PACKAGE_SENDERS = mapOf(
            "com.google.android.apps.nbu.paisa.user" to "GPAY",
            "com.phonepe.app" to "PHONEPE",
            "net.one97.paytm" to "PAYTM",
            "com.paytm.business" to "PAYTMB",
            "com.google.android.apps.messaging" to MESSAGES,
            "com.android.messaging" to MESSAGES,
            "com.samsung.android.messaging" to MESSAGES,
            // Bank apps vary by version; these are the ids seen in the wild
            // most often, still to be confirmed on the test phone.
            "com.snapwork.hdfc" to "HDFC",
            "com.csam.icici.bank.imobile" to "ICICI",
            "com.sbi.lotusintouch" to "SBI",
            "com.axis.mobile" to "AXIS",
            "com.bankofbaroda.mconnect" to "BOB",
        )
    }
}
