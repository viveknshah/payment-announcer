package dev.viveknshah.payannouncer.voice

import dev.viveknshah.payannouncer.ledger.DailyStats
import dev.viveknshah.payannouncer.parser.PaymentRecord
import dev.viveknshah.payannouncer.parser.PaymentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HindiSpeechTest {

    @Test
    fun creditedPaymentWithANameSpeaksTheName() {
        val text = HindiAnnouncement.text(record(amount = 25_000, payer = "Ravi"))

        assertEquals("Ravi से 250 रुपये मिले।", text)
    }

    @Test
    fun creditedPaymentWithoutANameStaysGeneric() {
        val text = HindiAnnouncement.text(record(amount = 3_400))

        assertEquals("34 रुपये मिले।", text)
    }

    @Test
    fun vpaIsNotSpokenAsAName() {
        val text = HindiAnnouncement.text(
            record(amount = 25_000, payer = "ravi.k@upi"),
        )

        assertEquals("250 रुपये मिले।", text)
    }

    @Test
    fun largePaymentIsAnnouncedAsLarge() {
        val text = HindiAnnouncement.text(
            record(amount = 500_000, payer = "Ravi", large = true),
        )

        assertEquals("बड़ा भुगतान। 5,000 रुपये मिले।", text)
    }

    @Test
    fun refundAndReviewHaveTheirOwnLines() {
        assertEquals(
            "45 रुपये का रिफंड मिला।",
            HindiAnnouncement.text(record(amount = 4_500, status = PaymentStatus.REFUND)),
        )
        assertEquals(
            "भुगतान जाँचें। 250 रुपये।",
            HindiAnnouncement.text(record(amount = 25_000, status = PaymentStatus.REVIEW)),
        )
    }

    @Test
    fun duplicatesAndExcludedMessagesStaySilent() {
        assertNull(HindiAnnouncement.text(record(amount = 25_000, duplicate = true)))
        assertNull(
            HindiAnnouncement.text(record(amount = 0, status = PaymentStatus.EXCLUDED)),
        )
    }

    @Test
    fun summaryMatchesTheCorpusFigures() {
        val stats = stats(
            count = 8,
            total = 571_600,
            average = 71_450,
            busiestHour = 10,
            largest = 500_000,
            refundCount = 1,
            refundTotal = 4_500,
            reviewCount = 1,
            duplicateCount = 2,
        )

        assertEquals(
            "आज 8 भुगतान, कुल 5,716 रुपये। औसत 714 रुपये, सबसे बड़ा 5,000 रुपये। " +
                "सबसे ज़्यादा भुगतान 10 बजे। 1 रिफंड, 45 रुपये। " +
                "1 भुगतान जाँचने हैं। 2 डुप्लिकेट छोड़े गए।",
            HindiSummary.text(stats),
        )
    }

    @Test
    fun emptyDayIsHonestAndStillMentionsRefunds() {
        assertEquals(
            "आज कोई बिक्री नहीं हुई।",
            HindiSummary.text(stats(count = 0, total = 0, average = 0, largest = 0)),
        )
        assertEquals(
            "आज कोई बिक्री नहीं हुई। 1 रिफंड, 45 रुपये।",
            HindiSummary.text(
                stats(
                    count = 0,
                    total = 0,
                    average = 0,
                    largest = 0,
                    refundCount = 1,
                    refundTotal = 4_500,
                ),
            ),
        )
    }

    private fun record(
        amount: Long,
        payer: String? = null,
        status: String = PaymentStatus.CREDITED,
        large: Boolean = false,
        duplicate: Boolean = false,
    ) = PaymentRecord(
        id = "id",
        sender = "GPAY",
        amountPaise = amount,
        payer = payer,
        txnId = null,
        timestamp = "2026-10-20T10:15:00",
        status = status,
        source = "payment_app",
        needsReview = false,
        large = large,
        duplicate = duplicate,
        dedupeKey = "key",
        dedupeUncertain = false,
        reason = "",
        ingestedAt = "2026-10-20T10:15:00",
    )

    private fun stats(
        count: Int,
        total: Long,
        average: Long,
        largest: Long,
        busiestHour: Int? = null,
        refundCount: Int = 0,
        refundTotal: Long = 0,
        reviewCount: Int = 0,
        duplicateCount: Int = 0,
    ) = DailyStats(
        count = count,
        totalPaise = total,
        averagePaise = average,
        busiestHour = busiestHour,
        largestPaise = largest,
        refundCount = refundCount,
        refundTotalPaise = refundTotal,
        reviewCount = reviewCount,
        duplicateCount = duplicateCount,
        sales = emptyList(),
        refunds = emptyList(),
        review = emptyList(),
    )
}
