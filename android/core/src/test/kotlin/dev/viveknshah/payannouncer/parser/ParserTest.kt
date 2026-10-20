package dev.viveknshah.payannouncer.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Port of the parser cases in `test_suite.py`.
 *
 * The Python suite remains the oracle. When a rule changes, change both suites
 * in the same commit and keep the corpus totals identical.
 */
class ParserTest {

    private val now = LocalDateTime.of(2026, 10, 20, 13, 0)
    private val config = ParserConfig.CHAI_STALL

    // --- amounts -----------------------------------------------------------

    @Test
    fun plainAmount() {
        assertEquals(15000L, Parser.amountToPaise("150"))
    }

    @Test
    fun decimalAmounts() {
        assertEquals(3400L, Parser.amountToPaise("34.00"))
        assertEquals(4505L, Parser.amountToPaise("45.05"))
    }

    @Test
    fun indianCommas() {
        assertEquals(1245000L, Parser.amountToPaise("12,450"))
        assertEquals(12345650L, Parser.amountToPaise("1,23,456.50"))
    }

    @Test
    fun indianDigitGrouping() {
        assertEquals("2,450", Parser.formatRupees(245000))
        assertEquals("12,34,567", Parser.formatRupees(123456700))
        assertEquals("34", Parser.formatRupees(3400))
    }

    // --- real bank layouts -------------------------------------------------

    @Test
    fun bobCreditAlert() {
        val record = Parser.parseSms("VK-BOBSMS", BOB_1, config, now)
        assertEquals(PaymentStatus.CREDITED, record.status)
        assertEquals(3400L, record.amountPaise)
        assertEquals("ravi.k@upi", record.payer)
        assertEquals("100000000001", record.txnId)
        assertEquals(PaymentSource.BANK_SMS, record.source)
        assertEquals("2026-10-20", record.timestamp.take(10))
        assertEquals("0c8c7718", record.id) // same SHA-1 prefix as sms_parser.py
        assertFalse(record.needsReview)
    }

    @Test
    fun bobPlainCredit() {
        val record = Parser.parseSms("VK-BOBSMS", BOB_2, config, now)
        assertEquals(PaymentStatus.CREDITED, record.status)
        assertEquals(3700L, record.amountPaise)
        assertNull(record.payer)
        assertNull(record.txnId)
        assertTrue(record.dedupeUncertain)
        assertEquals("2026-10-20T17:13:00", record.timestamp)
        assertEquals("amt:3700@2026-10-20 17:13", record.dedupeKey)
    }

    @Test
    fun balanceNeverAppearsInRecord() {
        val record = Parser.parseSms("VK-BOBSMS", BOB_2, config, now)
        val blob = record.toString()
        assertFalse(blob.contains("10,000"))
        assertFalse(blob.contains("10000"))
    }

    // --- illustrative formats ---------------------------------------------

    @Test
    fun exampleWithPayerAndTransactionId() {
        val record = Parser.parseSms(
            "AX-PAYTMB",
            "You have received ₹150 from Rahul via UPI. Transaction ID: 123456789012.",
            config,
            now,
        )
        assertEquals(PaymentStatus.CREDITED, record.status)
        assertEquals(15000L, record.amountPaise)
        assertEquals("Rahul", record.payer)
        assertEquals("123456789012", record.txnId)
        assertEquals(PaymentSource.PAYTM_BUSINESS, record.source)
    }

    @Test
    fun exampleUpiRefNo() {
        val record = Parser.parseSms(
            "VK-HDFCBK",
            "Payment of Rs. 80 received successfully. UPI Ref No: 987654321012.",
            config,
            now,
        )
        assertEquals(PaymentStatus.CREDITED, record.status)
        assertEquals(8000L, record.amountPaise)
        assertEquals("987654321012", record.txnId)
    }

    @Test
    fun exampleNoPayer() {
        val record = Parser.parseSms(
            "AX-ICICIB",
            "₹250 credited to your account through UPI. Ref: 456789123012.",
            config,
            now,
        )
        assertEquals(PaymentStatus.CREDITED, record.status)
        assertEquals(25000L, record.amountPaise)
        assertNull(record.payer)
        assertEquals("456789123012", record.txnId)
    }

    @Test
    fun exampleNoTransactionId() {
        val record = Parser.parseSms(
            "AX-PAYTMB",
            "You received a payment of ₹45.00. Status: Successful.",
            config,
            now,
        )
        assertEquals(PaymentStatus.CREDITED, record.status)
        assertEquals(4500L, record.amountPaise)
        assertNull(record.txnId)
        assertTrue(record.dedupeUncertain)
    }

    @Test
    fun exampleBankReference() {
        val record = Parser.parseSms(
            "VK-SBISMS",
            "Your account has been credited with INR 120.00. UPI transaction reference: 223456789012.",
            config,
            now,
        )
        assertEquals(PaymentStatus.CREDITED, record.status)
        assertEquals(12000L, record.amountPaise)
        assertEquals("223456789012", record.txnId)
    }

    // --- decoys ------------------------------------------------------------

    @Test
    fun paymentRequestExcluded() {
        val record = Parser.parseSms(
            "AX-PAYTMB",
            "You have requested ₹500 from Ramesh via UPI. Awaiting approval.",
            config,
            now,
        )
        assertEquals(PaymentStatus.EXCLUDED, record.status)
        assertTrue(record.reason.contains("payment_request"))
    }

    @Test
    fun failedPaymentExcluded() {
        val record = Parser.parseSms(
            "AX-PAYTMB",
            "Payment of Rs. 90 failed. Please retry.",
            config,
            now,
        )
        assertEquals(PaymentStatus.EXCLUDED, record.status)
        assertTrue(record.reason.contains("failed_payment"))
    }

    @Test
    fun outgoingPaymentExcludedAndBalanceIgnored() {
        val record = Parser.parseSms(
            "VK-BOBSMS",
            "Rs. 60.00 debited from A/c XX0000 on 20-10-2026. Avl Bal: Rs.9,940.00",
            config,
            now,
        )
        assertEquals(PaymentStatus.EXCLUDED, record.status)
        assertTrue(record.reason.contains("outgoing_money"))
        assertEquals(6000L, record.amountPaise)
    }

    @Test
    fun otpExcludedBeforeAmountIsRead() {
        val record = Parser.parseSms(
            "VK-BOBSMS",
            "Your OTP for transaction of Rs. 200 is 123456. Do not share it with anyone.",
            config,
            now,
        )
        assertEquals(PaymentStatus.EXCLUDED, record.status)
        assertTrue(record.reason.contains("otp_message"))
        assertEquals(0L, record.amountPaise)
    }

    @Test
    fun linkGoesToReviewEvenFromAllowlistedSender() {
        val record = Parser.parseSms(
            "VK-BOBSMS",
            "Rs. 500 credited to your wallet. Claim now at http://paytm-cashback.example.com",
            config,
            now,
        )
        assertEquals(PaymentStatus.REVIEW, record.status)
        assertTrue(record.reason.contains("contains_link"))
    }

    @Test
    fun unknownSenderStoppedByAllowlist() {
        val record = Parser.parseSms(
            "9876543210",
            "Hi! Rs. 500 credited to your wallet. Claim now at http://paytm-cashback.example.com",
            config,
            now,
        )
        assertEquals(PaymentStatus.REVIEW, record.status)
        assertTrue(record.reason.contains("sender_not_in_allowlist"))
        assertEquals(0L, record.amountPaise)
    }

    @Test
    fun unknownSenderFlaggedWhenAllowlistIsOpen() {
        val record = Parser.parseSms(
            "9876543210",
            "Rs. 500 credited to your account.",
            config.copy(senderAllowlist = listOf("*")),
            now,
        )
        assertEquals(PaymentStatus.CREDITED, record.status)
        assertTrue(record.needsReview)
        assertTrue(record.reason.contains("unknown_sender"))
    }

    @Test
    fun refundRecordedSeparately() {
        val record = Parser.parseSms(
            "AX-PAYTMB",
            "Refund of Rs. 45.00 credited to your account. UPI Ref No: 555666777888.",
            config,
            now,
        )
        assertEquals(PaymentStatus.REFUND, record.status)
        assertEquals(4500L, record.amountPaise)
    }

    @Test
    fun noCreditEvidenceGoesToReview() {
        val record = Parser.parseSms(
            "VK-BOBSMS",
            "Your UPI mandate of Rs. 300 is due tomorrow.",
            config,
            now,
        )
        assertEquals(PaymentStatus.REVIEW, record.status)
        assertTrue(record.reason.contains("no_credit_evidence"))
    }

    @Test
    fun bareNumberIsNotAReference() {
        val record = Parser.parseSms(
            "VK-BOBSMS",
            "Rs. 75 credited to A/c XX1234 on 20-10-2026.",
            config,
            now,
        )
        assertNull(record.txnId)
    }

    // --- threshold ---------------------------------------------------------

    @Test
    fun largePaymentFlagged() {
        val record = Parser.parseSms(
            "AX-PAYTMB",
            "You have received ₹5,000 from Suresh via UPI. Transaction ID: 999888777666.",
            config,
            now,
        )
        assertEquals(PaymentStatus.CREDITED, record.status)
        assertTrue(record.large)
    }

    @Test
    fun belowThresholdNotFlagged() {
        val record = Parser.parseSms(
            "AX-PAYTMB",
            "You have received ₹150 from Rahul via UPI. Transaction ID: 123456789012.",
            config,
            now,
        )
        assertFalse(record.large)
    }

    // --- whole corpus parity ----------------------------------------------

    /**
     * Reads the same 16-message corpus as `python app.py demo`. The demo's
     * dedupe happens in the ledger, so this test dedupes on `dedupeKey` and
     * checks the numbers the post claims: 8 counted, ₹5,716.
     */
    @Test
    fun sampleCorpusMatchesPythonDemo() {
        val corpus = File("../../sample_sms.txt")
        assumeTrue("sample_sms.txt not found; run from android/core", corpus.exists())

        val records = corpus.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split(" | ")
                val at = if (parts.size == 3) {
                    LocalDateTime.parse(parts[1], DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                } else {
                    now
                }
                Parser.parseSms(parts.first(), parts.last(), config, at)
            }

        // Field-by-field oracle generated by sms_parser.py from the same corpus.
        // Regenerate it with tools/export_parser_golden.py after a rule change.
        val golden = requireNotNull(javaClass.getResource("/sample_expected.tsv")) {
            "sample_expected.tsv is missing from test resources"
        }.readText()
            .lineSequence()
            .filter { it.isNotBlank() }
            .drop(1)
            .map { it.split("\t") }
            .toList()

        assertEquals(records.size, golden.size)
        records.zip(golden).forEach { (record, row) ->
            assertEquals(row[0], record.sender)
            assertEquals(row[1], record.status)
            assertEquals(row[2].toLong(), record.amountPaise)
            assertEquals(row[3].ifEmpty { null }, record.payer)
            assertEquals(row[4].ifEmpty { null }, record.txnId)
            assertEquals(row[5], record.timestamp)
            assertEquals(row[6], record.source)
            assertEquals(row[7].toBoolean(), record.needsReview)
            assertEquals(row[8].toBoolean(), record.large)
            assertEquals(row[9], record.dedupeKey)
            assertEquals(row[10].toBoolean(), record.dedupeUncertain)
            assertEquals(row[11], record.reason)
            assertEquals(row[12], record.id)
        }

        assertEquals(16, records.size)
        assertEquals(10, records.count { it.status == PaymentStatus.CREDITED })
        assertEquals(4, records.count { it.status == PaymentStatus.EXCLUDED })
        assertEquals(1, records.count { it.status == PaymentStatus.REFUND })
        assertEquals(1, records.count { it.status == PaymentStatus.REVIEW })

        val uniqueSales = records
            .filter { it.status == PaymentStatus.CREDITED }
            .distinctBy { it.dedupeKey }
        assertEquals(8, uniqueSales.size)
        assertEquals(571600L, uniqueSales.sumOf { it.amountPaise })
        assertEquals(1, uniqueSales.count { it.large })
    }

    private companion object {
        const val BOB_1 =
            "Credit Alert! Rs.34.00 credited to Bank of Baroda A/c XX0000 on 20-10-26 " +
                "from VPA ravi.k@upi (UPI 100000000001)"
        const val BOB_2 =
            "Rs. 37.00 credited to A/c XX0000 on 20-10-2026 17:13. Avl Bal: Rs.10,000.00"
    }
}
