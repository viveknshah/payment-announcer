package dev.viveknshah.payannouncer.voice

import dev.viveknshah.payannouncer.ledger.DailyStats
import dev.viveknshah.payannouncer.parser.Parser

/**
 * Deterministic end-of-day Hindi summary. No model, no network, and every
 * number comes straight from [DailyStats], so the spoken total cannot drift
 * from the ledger.
 */
object HindiSummary {

    fun text(stats: DailyStats): String {
        val parts = mutableListOf<String>()

        if (stats.count == 0) {
            parts += "आज कोई बिक्री नहीं हुई।"
        } else {
            parts += "आज ${stats.count} भुगतान, कुल ${rupees(stats.totalPaise)} रुपये।"
            parts += "औसत ${rupees(stats.averagePaise)} रुपये, " +
                "सबसे बड़ा ${rupees(stats.largestPaise)} रुपये।"
            stats.busiestHour?.let { parts += "सबसे ज़्यादा भुगतान $it बजे।" }
        }

        if (stats.refundCount > 0) {
            parts += "${stats.refundCount} रिफंड, ${rupees(stats.refundTotalPaise)} रुपये।"
        }
        if (stats.reviewCount > 0) {
            parts += "${stats.reviewCount} भुगतान जाँचने हैं।"
        }
        if (stats.duplicateCount > 0) {
            parts += "${stats.duplicateCount} डुप्लिकेट छोड़े गए।"
        }

        return parts.joinToString(" ")
    }

    private fun rupees(paise: Long): String = Parser.formatRupees(paise)
}
