package dev.viveknshah.payannouncer.voice

import dev.viveknshah.payannouncer.parser.Parser
import dev.viveknshah.payannouncer.parser.PaymentRecord
import dev.viveknshah.payannouncer.parser.PaymentStatus

/**
 * Hindi text for one ledger record.
 *
 * The app speaks only what the ledger counted. Duplicates and excluded
 * messages stay silent. A payer is spoken only when it looks like a name, not
 * like a VPA, so a raw UPI handle never goes to the speaker.
 */
object HindiAnnouncement {

    fun text(record: PaymentRecord): String? {
        if (record.duplicate) return null
        val amount = Parser.formatRupees(record.amountPaise)

        return when (record.status) {
            PaymentStatus.CREDITED -> when {
                record.large -> "बड़ा भुगतान। $amount रुपये मिले।"
                looksLikeName(record.payer) -> "${record.payer!!.trim()} से $amount रुपये मिले।"
                else -> "$amount रुपये मिले।"
            }

            PaymentStatus.REFUND -> "$amount रुपये का रिफंड मिला।"

            PaymentStatus.REVIEW -> if (record.amountPaise > 0) {
                "भुगतान जाँचें। $amount रुपये।"
            } else {
                "एक भुगतान जाँचना है।"
            }

            else -> null
        }
    }

    private fun looksLikeName(payer: String?): Boolean {
        val value = payer?.trim().orEmpty()
        if (value.length !in 2..40) return false
        if (value.any { it.isDigit() || it == '@' || it == '/' || it == '_' }) return false
        return value.any { it.isLetter() }
    }
}
