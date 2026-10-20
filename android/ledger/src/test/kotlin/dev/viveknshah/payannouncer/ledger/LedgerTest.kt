package dev.viveknshah.payannouncer.ledger

import dev.viveknshah.payannouncer.parser.Parser
import dev.viveknshah.payannouncer.parser.ParserConfig
import dev.viveknshah.payannouncer.parser.PaymentRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Port of the ledger cases in `test_suite.py`, plus the whole-corpus stats.
 * The Python ledger remains the oracle.
 */
class LedgerTest {

    private val config = ParserConfig.CHAI_STALL
    private val now = LocalDateTime.of(2026, 10, 20, 13, 0)
    private val day = LocalDate.of(2026, 10, 20)

    private fun parse(sender: String, body: String, at: LocalDateTime = now): PaymentRecord =
        Parser.parseSms(sender, body, config, at)

    @Test
    fun dedupeByReference() {
        val ledger = Ledger()
        val first = ledger.add(parse("VK-BOBSMS", BOB_1))
        val second = ledger.add(parse("VK-BOBSMS", BOB_1))

        assertFalse(first.duplicate)
        assertTrue(second.duplicate)
        assertTrue(second.needsReview)
        assertEquals("duplicate", second.reason)
        assertEquals(1, ledger.dailyStats(day).count)
        assertEquals(1, ledger.dailyStats(day).duplicateCount)
    }

    @Test
    fun uncertainDedupeByAmountAndTime() {
        val ledger = Ledger()
        val first = ledger.add(parse("VK-BOBSMS", BOB_2))
        val second = ledger.add(
            parse("VK-BOBSMS", BOB_2.replace("10,000.00", "10,037.00")),
        )

        assertEquals(first.dedupeKey, second.dedupeKey)
        assertFalse(first.duplicate)
        assertTrue(second.duplicate)
        assertTrue(second.dedupeUncertain)
    }

    @Test
    fun statsExcludeDecoysAndDuplicates() {
        val ledger = Ledger()
        ledger.add(parse("VK-BOBSMS", BOB_1))
        ledger.add(parse("VK-BOBSMS", BOB_1)) // duplicate
        ledger.add(parse("AX-PAYTMB", "Payment of Rs. 90 failed. Please retry."))
        ledger.add(
            parse(
                "AX-PAYTMB",
                "Refund of Rs. 45.00 credited to your account. UPI Ref No: 555666777888.",
            ),
        )

        val stats = ledger.dailyStats(day)
        assertEquals(1, stats.count)
        assertEquals(3400L, stats.totalPaise)
        assertEquals(1, stats.refundCount)
        assertEquals(1, stats.duplicateCount)
        assertEquals(0, stats.reviewCount)
    }

    @Test
    fun undoRemovesOnlyTheLastCountedSale() {
        val ledger = Ledger()
        ledger.add(parse("VK-BOBSMS", BOB_1))
        ledger.add(
            parse(
                "AX-PAYTMB",
                "Refund of Rs. 45.00 credited to your account. UPI Ref No: 555666777888.",
            ),
        )

        val removed = ledger.undoLastSale()
        assertEquals(3400L, removed?.amountPaise)
        assertEquals(0, ledger.dailyStats(day).count)
        assertEquals(1, ledger.dailyStats(day).refundCount)
        assertEquals(1, ledger.allRecords().size)
        assertNull(ledger.undoLastSale())
    }

    @Test
    fun excludedMessagesAreNeverDuplicates() {
        val ledger = Ledger()
        val failed = "Payment of Rs. 90 failed. Please retry."
        val first = ledger.add(parse("AX-PAYTMB", failed))
        val second = ledger.add(parse("AX-PAYTMB", failed))

        assertFalse(first.duplicate)
        assertFalse(second.duplicate)
        assertEquals(0, ledger.dailyStats(day).duplicateCount)
        assertEquals(0, ledger.dailyStats(day).count)
    }

    @Test
    fun duplicateKeepsExistingReviewReason() {
        val ledger = Ledger()
        val openConfig = config.copy(senderAllowlist = listOf("*"))
        val body = "Rs. 500 credited to your account."
        val first = Parser.parseSms("9876543210", body, openConfig, now)
        val second = Parser.parseSms("9876543210", body, openConfig, now)

        ledger.add(first)
        val stored = ledger.add(second)
        assertEquals("unknown_sender;duplicate", stored.reason)
    }

    @Test
    fun refundsDedupeSeparatelyFromSales() {
        val ledger = Ledger()
        val body = "Refund of Rs. 45.00 credited to your account. UPI Ref No: 555666777888."
        ledger.add(parse("AX-PAYTMB", body))
        val second = ledger.add(parse("AX-PAYTMB", body))

        assertTrue(second.duplicate)
        val stats = ledger.dailyStats(day)
        assertEquals(0, stats.count)
        assertEquals(1, stats.refundCount)
        assertEquals(4500L, stats.refundTotalPaise)
        assertEquals(1, stats.duplicateCount)
    }

    @Test
    fun dayRecordsFilterByPaymentDate() {
        val ledger = Ledger()
        ledger.add(parse("VK-BOBSMS", BOB_1)) // dated 2026-10-20 in the body
        ledger.add(
            parse("VK-BOBSMS", "Rs. 10 credited to A/c XX0000 on 19-10-2026."),
        )

        assertEquals(1, ledger.dayRecords(day).size)
        assertEquals(1, ledger.dayRecords(LocalDate.of(2026, 10, 19)).size)
        assertEquals(2, ledger.allRecords().size)
    }

    @Test
    fun averageBusiestHourAndLargest() {
        val ledger = Ledger()
        ledger.add(parse("VK-BOBSMS", "Rs. 100 credited to A/c XX0000 on 20-10-2026 10:05."))
        ledger.add(parse("VK-BOBSMS", "Rs. 200 credited to A/c XX0000 on 20-10-2026 10:45."))
        ledger.add(parse("VK-BOBSMS", "Rs. 500 credited to A/c XX0000 on 20-10-2026 13:10."))

        val stats = ledger.dailyStats(day)
        assertEquals(3, stats.count)
        assertEquals(80000L, stats.totalPaise)
        assertEquals(26666L, stats.averagePaise)
        assertEquals(10, stats.busiestHour)
        assertEquals(50000L, stats.largestPaise)
    }

    /**
     * End-to-end parity with `python app.py demo`: the same 16 messages, the
     * same duplicate pass, the same day's numbers.
     */
    @Test
    fun sampleCorpusMatchesPythonLedger() {
        val corpus = File("../../sample_sms.txt")
        assumeTrue("sample_sms.txt not found; run from android/ledger", corpus.exists())

        val ledger = Ledger()
        corpus.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val parts = line.split(" | ")
                val at = if (parts.size == 3) {
                    LocalDateTime.parse(parts[1], DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                } else {
                    now
                }
                ledger.add(Parser.parseSms(parts.first(), parts.last(), config, at))
            }

        val stats = ledger.dailyStats(day)
        assertEquals(16, ledger.allRecords().size)
        assertEquals(8, stats.count)
        assertEquals(571600L, stats.totalPaise)
        assertEquals(71450L, stats.averagePaise)
        assertEquals(10, stats.busiestHour)
        assertEquals(500000L, stats.largestPaise)
        assertEquals(1, stats.refundCount)
        assertEquals(4500L, stats.refundTotalPaise)
        assertEquals(1, stats.reviewCount)
        assertEquals(2, stats.duplicateCount)
    }

    private companion object {
        const val BOB_1 =
            "Credit Alert! Rs.34.00 credited to Bank of Baroda A/c XX0000 on 20-10-26 " +
                "from VPA ravi.k@upi (UPI 100000000001)"
        const val BOB_2 =
            "Rs. 37.00 credited to A/c XX0000 on 20-10-2026 17:13. Avl Bal: Rs.10,000.00"
    }
}
