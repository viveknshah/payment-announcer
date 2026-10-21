package dev.viveknshah.payannouncer.intake

import dev.viveknshah.payannouncer.ledger.Ledger
import dev.viveknshah.payannouncer.parser.ParserConfig
import dev.viveknshah.payannouncer.parser.PaymentRecord
import java.time.ZoneId
import java.util.Locale

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

    private val textIntake = PaymentIntake(ledger, config, zoneId)

    fun ingest(snapshot: NotificationSnapshot): PaymentRecord? {
        if (snapshot.groupSummary) return null
        val sender = senderFor(snapshot) ?: return null
        val body = snapshot.body()
        if (body.isBlank()) return null
        return textIntake.ingest(sender, body, snapshot.postedAtMillis)
    }

    private fun senderFor(snapshot: NotificationSnapshot): String? {
        val mapped = PACKAGE_SENDERS[snapshot.packageName] ?: return null
        if (mapped != MESSAGES) return mapped

        // Google Messages often titles a bank conversation "Messages" (or a
        // contact name) and puts the bank sender at the start of the message:
        // "VK-BOBSMS: Credit Alert! ...". Prefer a recognised title, then a
        // recognised body prefix. Ignore an unrecognised conversation instead
        // of turning every personal chat into a review row.
        val title = snapshot.title?.trim().orEmpty()
        resolveSender(title)?.let { return it }

        val message = listOf(snapshot.bigText, snapshot.text)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
            .orEmpty()
        val prefix = message.substringBefore(':', missingDelimiterValue = "").trim()
        resolveSender(prefix)?.let { return it }

        return null
    }

    /**
     * Keep the raw value when it already contains a configured token (so a
     * record keeps "VK-BOBSMS"), and map common bank display names to the
     * token the parser knows ("Bank of Baroda" -> "BOB").
     */
    private fun resolveSender(value: String): String? {
        val text = value.trim()
        if (text.isEmpty()) return null
        val upper = text.uppercase(Locale.ROOT)
        if (config.senderAllowlist.any { upper.contains(it) }) return text
        return DISPLAY_ALIASES.entries
            .firstOrNull { (name, _) -> upper.contains(name) }
            ?.value
    }

    private companion object {
        const val MESSAGES = "MESSAGES"

        /**
         * Google Messages and bank apps sometimes use a display name instead
         * of the SMS sender token. These map to the same parser tokens.
         */
        val DISPLAY_ALIASES = mapOf(
            "BANK OF BARODA" to "BOB",
            "STATE BANK OF INDIA" to "SBI",
            "HDFC BANK" to "HDFC",
            "ICICI BANK" to "ICICI",
            "AXIS BANK" to "AXIS",
            "KOTAK MAHINDRA" to "KOTAK",
            "PUNJAB NATIONAL" to "PNB",
            "CANARA BANK" to "CANARA",
            "UNION BANK" to "UNION",
            "YES BANK" to "YESB",
        )

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
