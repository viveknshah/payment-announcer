package dev.viveknshah.payannouncer.intake

import dev.viveknshah.payannouncer.ledger.Ledger
import dev.viveknshah.payannouncer.parser.Parser
import dev.viveknshah.payannouncer.parser.ParserConfig
import dev.viveknshah.payannouncer.parser.PaymentRecord
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Common parse-and-record path for notifications and SMS. Keeping it in one
 * place means both intake sources get the same parser and cross-source ledger
 * rules.
 */
class PaymentIntake(
    private val ledger: Ledger,
    private val config: ParserConfig,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {

    fun ingest(sender: String, body: String, atMillis: Long): PaymentRecord? {
        if (body.isBlank()) return null
        val at = if (atMillis > 0) {
            Instant.ofEpochMilli(atMillis).atZone(zoneId).toLocalDateTime()
        } else {
            LocalDateTime.now(zoneId)
        }
        return ledger.add(Parser.parseSms(sender, body, config, at))
    }
}
