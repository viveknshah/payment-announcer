package dev.viveknshah.payannouncer.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.viveknshah.payannouncer.ledger.Ledger
import dev.viveknshah.payannouncer.ledger.LedgerStats
import dev.viveknshah.payannouncer.parser.Parser
import dev.viveknshah.payannouncer.parser.ParserConfig
import dev.viveknshah.payannouncer.parser.PaymentRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The storage adapter is thin, but these tests prove the important parts:
 * every field round-trips, dedupe sees earlier rows, undo rewrites in order,
 * and a file database still has the records after reopening.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomLedgerStoreTest {

    private lateinit var db: LedgerDatabase
    private lateinit var store: RoomLedgerStore

    private val config = ParserConfig.CHAI_STALL
    private val now = LocalDateTime.of(2026, 10, 20, 13, 0)
    private val day = LocalDate.of(2026, 10, 20)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LedgerDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = RoomLedgerStore(db.paymentDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun roundTripsEveryField() {
        val record = parse(BOB_1)
        store.append(record)

        assertEquals(listOf(record), store.all())
    }

    @Test
    fun dedupeSeesRowsFromEarlierStoreInstances() {
        val first = Ledger(store)
        assertEquals(3400L, first.add(parse(BOB_1)).amountPaise)

        val secondLedger = Ledger(RoomLedgerStore(db.paymentDao()))
        val second = secondLedger.add(parse(BOB_1))

        assertTrue(second.duplicate)
        assertEquals("duplicate", second.reason)
        assertEquals(2, store.all().size)
        assertEquals(1, secondLedger.dailyStats(day).count)
    }

    @Test
    fun undoRewritesTheTableAndKeepsTheRefund() {
        val ledger = Ledger(store)
        val credit = parse(BOB_1)
        val refund = parse(REFUND)
        ledger.add(credit)
        ledger.add(refund)

        val removed = ledger.undoLastSale()

        assertEquals(3400L, removed?.amountPaise)
        assertEquals(listOf(refund), store.all())
        assertEquals(0, ledger.dailyStats(day).count)
        assertEquals(1, ledger.dailyStats(day).refundCount)
        assertNull(ledger.undoLastSale())
    }

    @Test
    fun undoRemovesTheOnlyRowWithoutBreakingTheRewrite() {
        val ledger = Ledger(store)
        ledger.add(parse(BOB_1))

        assertEquals(3400L, ledger.undoLastSale()?.amountPaise)
        assertEquals(emptyList<PaymentRecord>(), store.all())
    }

    @Test
    fun crossSourceDedupeWorksThroughRoom() {
        val ledger = Ledger(store, crossSourceWindowSeconds = 120)
        ledger.add(
            Parser.parseSms(
                "VK-BOBSMS",
                BOB_3,
                config,
                LocalDateTime.of(2026, 10, 20, 10, 15),
            ),
        )
        val app = Parser.parseSms(
            "GPAY",
            "₹34.00 received from Ravi",
            config,
            LocalDateTime.of(2026, 10, 20, 10, 16),
        )

        val stored = ledger.add(app)

        assertTrue(stored.duplicate)
        assertEquals("cross_source_duplicate", stored.reason)
        assertEquals(2, store.all().size)
        assertEquals(1, ledger.dailyStats(day).count)
    }

    @Test
    fun excludedRowsDoNotBlockARealCountedKey() {
        val ledger = Ledger(store)
        val failed = "Payment of Rs. 90 failed. Please retry."
        ledger.add(Parser.parseSms("AX-PAYTMB", failed, config, now))
        ledger.add(Parser.parseSms("AX-PAYTMB", failed, config, now))
        ledger.add(parse(BOB_1))

        val stats = LedgerStats.compute(store.all())
        assertEquals(1, stats.count)
        assertEquals(0, stats.duplicateCount)
    }

    @Test
    fun fileDatabaseSurvivesReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "ledger-reopen-test.db"
        context.deleteDatabase(name)

        val firstDb = Room.databaseBuilder(context, LedgerDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            val record = parse(BOB_1)
            Ledger(RoomLedgerStore(firstDb.paymentDao())).add(record)
            firstDb.close()

            val secondDb = Room.databaseBuilder(context, LedgerDatabase::class.java, name)
                .allowMainThreadQueries()
                .build()
            try {
                assertEquals(
                    listOf(record),
                    RoomLedgerStore(secondDb.paymentDao()).all(),
                )
            } finally {
                secondDb.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun parse(body: String): PaymentRecord =
        Parser.parseSms("VK-BOBSMS", body, config, now)

    private companion object {
        const val BOB_1 =
            "Credit Alert! Rs.34.00 credited to Bank of Baroda A/c XX0000 on 20-10-26 " +
                "from VPA ravi.k@upi (UPI 100000000001)"
        const val REFUND =
            "Refund of Rs. 45.00 credited to your account. UPI Ref No: 555666777888."
        const val BOB_3 =
            "Credit Alert! Rs.34.00 credited to Bank of Baroda A/c XX0000 on 20-10-2026 " +
                "10:15 from VPA ravi.k@upi (UPI 100000000001)"
    }
}
