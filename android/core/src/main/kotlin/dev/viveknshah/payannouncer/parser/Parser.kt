package dev.viveknshah.payannouncer.parser

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Strict, rule-based UPI/bank SMS parser.
 *
 * This is a line-for-line port of `sms_parser.py` from the Python reference
 * implementation. The rules are intentionally not a model: money is handled as
 * integer paise, and only a clear success credit can become a sale.
 *
 * Guarantees carried over from the Python parser:
 * - amounts are parsed as integers in paise, never floats;
 * - refunds, failed payments, payment requests and outgoing debits never count;
 * - OTPs are rejected before anything else is read;
 * - balances are removed before the amount is searched for;
 * - a transaction id comes only from an explicit label, never from a bare number;
 * - messages without a reference deduplicate on amount + minute, flagged uncertain.
 */
object Parser {

    private const val AMOUNT_GROUP = 1

    private val AMOUNT_RE = Regex(
        """(?:₹|Rs\.?|INR)\s*([0-9][0-9,]*(?:\.\d{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )

    private val BALANCE_RE = Regex(
        """(?:Avl\.?\s*Bal(?:ance)?|available\s+balance)\s*[:\-]?\s*(?:₹|Rs\.?|INR)\s*[0-9][0-9,]*(?:\.\d{1,2})?""",
        RegexOption.IGNORE_CASE,
    )

    private val OTP_RE = Regex(
        """\b(OTP|O\.T\.P|one[- ]time password)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val LINK_RE = Regex(
        """(https?://\S+|www\.\S+)""",
        RegexOption.IGNORE_CASE,
    )

    /** Transaction ids are captured only from explicit labels, in this order. */
    private val REF_PATTERNS = listOf(
        Regex("""Transaction\s*ID\s*[:\-]?\s*([A-Za-z0-9]{6,})""", RegexOption.IGNORE_CASE),
        Regex("""UPI\s+Ref(?:erence)?\s*No\.?\s*[:\-]?\s*([A-Za-z0-9]{6,})""", RegexOption.IGNORE_CASE),
        Regex("""UPI\s+transaction\s+reference\s*[:\-]?\s*([A-Za-z0-9]{6,})""", RegexOption.IGNORE_CASE),
        Regex("""\bRef(?:erence)?\s*(?:No)?\.?\s*[:\-]\s*([A-Za-z0-9]{6,})""", RegexOption.IGNORE_CASE),
        Regex("""\(UPI\s+([0-9]{9,18})\)""", RegexOption.IGNORE_CASE),
        Regex("""\bUPI\s+([0-9]{9,18})\b"""),
    )

    private val PAYER_PATTERNS = listOf(
        Regex("""\bfrom\s+VPA\s+([A-Za-z0-9._-]+@[A-Za-z]+)""", RegexOption.IGNORE_CASE),
        Regex("""\bfrom\s+([A-Za-z][A-Za-z .]{1,30}?)\s+via\s+UPI\b""", RegexOption.IGNORE_CASE),
    )

    private val CREDIT_WORDS = listOf("credited", "received", "deposited")
    private val REQUEST_WORDS = listOf("requested", "request of", "payment request", "requesting")
    private val FAILED_WORDS = listOf("failed", "failure", "declined", "unsuccessful", "rejected")
    private val OUTGOING_WORDS = listOf("debited", "sent to", "paid to", "withdrawn")
    private val REFUND_WORDS = listOf("refund", "reversed", "reversal", "chargeback")

    // Indian SMS dates are day-first: DD-MM-YY(YY), optional HH:MM.
    private val DATE_PATTERNS = listOf(
        Regex("""\b(\d{2})-(\d{2})-(\d{4})\s+(\d{2}):(\d{2})\b"""),
        Regex("""\b(\d{2})-(\d{2})-(\d{2})\s+(\d{2}):(\d{2})\b"""),
        Regex("""\b(\d{2})-(\d{2})-(\d{4})\b"""),
        Regex("""\b(\d{2})-(\d{2})-(\d{2})\b"""),
    )

    private val SECOND_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private val MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * Parse one SMS into a [PaymentRecord]. Never throws for ordinary input.
     *
     * @param now ingestion time, also the timestamp fallback when the SMS has
     *   no date. Pass a fixed value in tests to make records reproducible.
     */
    fun parseSms(
        sender: String,
        body: String,
        config: ParserConfig,
        now: LocalDateTime = LocalDateTime.now(),
    ): PaymentRecord {
        val cleanSender = sender.trim()
        val cleanBody = body.trim()
        val lower = cleanBody.lowercase(Locale.ROOT)

        val reasons = mutableListOf<String>()
        var status = PaymentStatus.CREDITED
        var amountPaise: Long? = null
        var payer: String? = null
        var txnId: String? = null

        // Hard safety gates first.
        if (OTP_RE.containsMatchIn(cleanBody)) {
            status = PaymentStatus.EXCLUDED
            reasons += "otp_message"
        } else if (!senderAllowed(cleanSender, config)) {
            status = PaymentStatus.REVIEW
            reasons += "sender_not_in_allowlist"
        } else {
            if (LINK_RE.containsMatchIn(cleanBody)) {
                // A link in a payment SMS is a classic phishing sign.
                status = PaymentStatus.REVIEW
                reasons += "contains_link"
            }

            amountPaise = findAmountPaise(cleanBody)
            if (amountPaise == null) {
                status = PaymentStatus.REVIEW
                reasons += "no_amount_found"
            } else {
                when {
                    REQUEST_WORDS.any(lower::contains) -> {
                        status = PaymentStatus.EXCLUDED
                        reasons += "payment_request"
                    }
                    FAILED_WORDS.any(lower::contains) -> {
                        status = PaymentStatus.EXCLUDED
                        reasons += "failed_payment"
                    }
                    OUTGOING_WORDS.any(lower::contains) -> {
                        status = PaymentStatus.EXCLUDED
                        reasons += "outgoing_money"
                    }
                    REFUND_WORDS.any(lower::contains) -> {
                        status = PaymentStatus.REFUND
                        reasons += "refund_recorded_separately"
                    }
                    !CREDIT_WORDS.any(lower::contains) -> {
                        // No clear evidence of a successful credit: do not count it.
                        status = PaymentStatus.REVIEW
                        reasons += "no_credit_evidence"
                    }
                }
            }

            if (status == PaymentStatus.CREDITED) {
                txnId = findTxnId(cleanBody)
                payer = findPayer(cleanBody)
            }
        }

        val source = classifySource(cleanSender, config)
        var needsReview = status == PaymentStatus.REVIEW
        if (source == PaymentSource.UNKNOWN &&
            (status == PaymentStatus.CREDITED || status == PaymentStatus.REFUND)
        ) {
            needsReview = true
            reasons += "unknown_sender"
        }

        val timestamp = findTimestamp(cleanBody, now)
        val large = amountPaise != null && amountPaise >= config.largeAmountThresholdPaise

        val hasRef = txnId != null
        val dedupeKey = if (hasRef) {
            "ref:$txnId"
        } else {
            // Matches the Python f-string, including "None" when no amount exists.
            "amt:${amountPaise ?: "None"}@${timestamp.format(MINUTE_FORMAT)}"
        }

        return PaymentRecord(
            id = recordId(cleanSender, cleanBody, now),
            sender = cleanSender,
            amountPaise = amountPaise ?: 0L,
            payer = payer,
            txnId = txnId,
            timestamp = timestamp.format(SECOND_FORMAT),
            status = status,
            source = source,
            needsReview = needsReview,
            large = large,
            duplicate = false,
            dedupeKey = dedupeKey,
            dedupeUncertain = !hasRef,
            reason = reasons.joinToString(";"),
            ingestedAt = now.format(SECOND_FORMAT),
        )
    }

    /** `"1,234.50"` -> `123450`; null when the text is not a number. */
    fun amountToPaise(text: String): Long? {
        val cleaned = text.replace(",", "").trim()
        return try {
            BigDecimal(cleaned)
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_EVEN)
                .toLong()
        } catch (_: NumberFormatException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    /** `245000` -> `"2,450"` using Indian digit grouping. */
    fun formatRupees(paise: Long): String {
        val rupees = paise / 100
        var s = rupees.toString()
        if (s.length > 3) {
            var head = s.dropLast(3)
            val tail = s.takeLast(3)
            val parts = ArrayDeque<String>()
            while (head.length > 2) {
                parts.addFirst(head.takeLast(2))
                head = head.dropLast(2)
            }
            if (head.isNotEmpty()) parts.addFirst(head)
            s = parts.joinToString(",") + "," + tail
        }
        return s
    }

    fun classifySource(sender: String, config: ParserConfig): String {
        val s = sender.uppercase(Locale.ROOT)
        if (config.paytmBusinessSenders.any { s.contains(it.uppercase(Locale.ROOT)) }) {
            return PaymentSource.PAYTM_BUSINESS
        }
        if (config.bankSenders.any { s.contains(it.uppercase(Locale.ROOT)) }) {
            return PaymentSource.BANK_SMS
        }
        if (config.paymentAppSenders.any { s.contains(it.uppercase(Locale.ROOT)) }) {
            return PaymentSource.PAYMENT_APP
        }
        return PaymentSource.UNKNOWN
    }

    fun senderAllowed(sender: String, config: ParserConfig): Boolean {
        if (config.senderAllowlist.any { it == "*" }) return true
        val s = sender.uppercase(Locale.ROOT)
        return config.senderAllowlist.any { s.contains(it.uppercase(Locale.ROOT)) }
    }

    /** First amount in the message, ignoring any available-balance clause. */
    private fun findAmountPaise(body: String): Long? {
        val withoutBalance = BALANCE_RE.replace(body, " ")
        val match = AMOUNT_RE.find(withoutBalance) ?: return null
        return amountToPaise(match.groupValues[AMOUNT_GROUP])
    }

    private fun findTxnId(body: String): String? {
        for (pattern in REF_PATTERNS) {
            val match = pattern.find(body) ?: continue
            return match.groupValues[1]
        }
        return null
    }

    private fun findPayer(body: String): String? {
        for (pattern in PAYER_PATTERNS) {
            val match = pattern.find(body) ?: continue
            return match.groupValues[1].trim()
        }
        return null
    }

    private fun findTimestamp(body: String, default: LocalDateTime): LocalDateTime {
        for (pattern in DATE_PATTERNS) {
            val match = pattern.find(body) ?: continue
            val groups = match.groupValues
            val day = groups[1].toIntOrNull() ?: continue
            val month = groups[2].toIntOrNull() ?: continue
            val rawYear = groups[3].toIntOrNull() ?: continue
            val year = if (rawYear < 100) rawYear + 2000 else rawYear
            val hasTime = groups.size >= 6
            val hour = if (hasTime) groups[4].toIntOrNull() ?: 12 else 12
            val minute = if (hasTime) groups[5].toIntOrNull() ?: 0 else 0
            try {
                return LocalDateTime.of(year, month, day, hour, minute)
            } catch (_: DateTimeException) {
                // Invalid date in an SMS: try the next pattern, then fall back.
            }
        }
        return default
    }

    /**
     * Python's `datetime.isoformat()` with microseconds when present. The id is
     * only a short fingerprint, but matching the reference keeps parity tests
     * and the Python/Android oracles comparable.
     */
    private fun recordId(sender: String, body: String, now: LocalDateTime): String {
        val stamp = buildString {
            append(now.format(SECOND_FORMAT))
            if (now.nano != 0) {
                append('.')
                append(String.format(Locale.ROOT, "%06d", now.nano / 1_000))
            }
        }
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$sender|$body|$stamp".toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}
