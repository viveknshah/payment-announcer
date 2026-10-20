package dev.viveknshah.payannouncer.intake

import dev.viveknshah.payannouncer.ledger.InMemoryLedgerStore
import dev.viveknshah.payannouncer.ledger.Ledger
import dev.viveknshah.payannouncer.parser.ParserConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class NotificationIntakeTest {

    private val config = ParserConfig.CHAI_STALL
    private val zone = ZoneOffset.UTC
    private val postedAt = Instant.parse("2026-10-20T10:15:00Z").toEpochMilli()
    private val day = LocalDate.of(2026, 10, 20)

    private fun newIntake(): Pair<NotificationIntake, InMemoryLedgerStore> {
        val store = InMemoryLedgerStore()
        return NotificationIntake(Ledger(store), config, zone) to store
    }

    @Test
    fun gpayNotificationBecomesACountedSale() {
        val (intake, store) = newIntake()

        val record = intake.ingest(
            snapshot(
                pkg = GPAY,
                title = "Payment received",
                text = "₹250 received from Ravi",
            ),
        )

        assertNotNull(record)
        assertEquals(25_000L, record!!.amountPaise)
        assertEquals("GPAY", record.sender)
        assertEquals("payment_app", record.source)
        assertEquals("credited", record.status)
        assertEquals("2026-10-20T10:15:00", record.timestamp)
        assertEquals(1, Ledger(store).dailyStats(day).count)
    }

    @Test
    fun expandedTextProvidesTheReferenceAndPayer() {
        val (intake, _) = newIntake()

        val record = intake.ingest(
            snapshot(
                pkg = GPAY,
                title = "GPay",
                text = "₹250 received",
                bigText = "₹250 received from Ravi via UPI (UPI 123456789012)",
            ),
        )

        assertEquals("123456789012", record?.txnId)
        assertEquals("Ravi", record?.payer)
        assertEquals("ref:123456789012", record?.dedupeKey)
    }

    @Test
    fun unmappedPackagesAreIgnored() {
        val (intake, store) = newIntake()

        val record = intake.ingest(
            snapshot(
                pkg = "com.whatsapp",
                title = "Ravi",
                text = "₹250 received",
            ),
        )

        assertNull(record)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun groupSummariesAreIgnored() {
        val (intake, store) = newIntake()

        val record = intake.ingest(
            snapshot(
                pkg = GPAY,
                title = "2 payments",
                text = "₹250 and ₹100 received",
                groupSummary = true,
            ),
        )

        assertNull(record)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun messagesTitleActsAsTheBankSender() {
        val (intake, _) = newIntake()

        val record = intake.ingest(
            snapshot(
                pkg = MESSAGES,
                title = "BOB",
                text = "Credit Alert! Rs.34.00 credited to Bank of Baroda A/c XX0000 " +
                    "on 20-10-26 from VPA ravi.k@upi (UPI 100000000001)",
            ),
        )

        assertEquals("BOB", record?.sender)
        assertEquals("bank_sms", record?.source)
        assertEquals(3_400L, record?.amountPaise)
        assertEquals("100000000001", record?.txnId)
    }

    @Test
    fun bankAppPackageMapsToItsBankToken() {
        val (intake, _) = newIntake()

        val record = intake.ingest(
            snapshot(
                pkg = "com.snapwork.hdfc",
                title = "HDFC Bank",
                text = "Rs. 500 credited to A/c XX0000 on 20-10-2026 10:15.",
            ),
        )

        assertEquals("HDFC", record?.sender)
        assertEquals("bank_sms", record?.source)
        assertEquals(50_000L, record?.amountPaise)
    }

    @Test
    fun repostedNotificationIsMarkedDuplicate() {
        val (intake, store) = newIntake()
        val notification = snapshot(
            pkg = GPAY,
            title = "Payment received",
            text = "₹250 received from Ravi",
        )

        intake.ingest(notification)
        val second = intake.ingest(notification)

        assertTrue(second!!.duplicate)
        assertEquals(2, store.all().size)
        val stats = Ledger(store).dailyStats(day)
        assertEquals(1, stats.count)
        assertEquals(1, stats.duplicateCount)
    }

    private fun snapshot(
        pkg: String,
        title: String?,
        text: String?,
        bigText: String? = null,
        groupSummary: Boolean = false,
    ) = NotificationSnapshot(
        packageName = pkg,
        title = title,
        text = text,
        bigText = bigText,
        subText = null,
        postedAtMillis = postedAt,
        groupSummary = groupSummary,
    )

    private companion object {
        const val GPAY = "com.google.android.apps.nbu.paisa.user"
        const val MESSAGES = "com.google.android.apps.messaging"
    }
}
