package dev.viveknshah.payannouncer.ledger

import dev.viveknshah.payannouncer.parser.PaymentRecord
import dev.viveknshah.payannouncer.parser.PaymentSource
import dev.viveknshah.payannouncer.parser.PaymentStatus
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import kotlin.math.abs

/**
 * Storage seam for the ledger.
 *
 * The in-memory implementation is used by tests and by the first Android
 * prototype. A Room-backed implementation belongs in the app module and only
 * has to provide these operations; the dedupe and stats rules stay here.
 */
interface LedgerStore {
    fun all(): List<PaymentRecord>

    /** True when a counted sale/refund already owns [dedupeKey]. */
    fun hasCountedKey(dedupeKey: String): Boolean

    fun append(record: PaymentRecord)
    fun rewrite(records: List<PaymentRecord>)
}

/** Test/prototype store. Not thread-safe; call it from one dispatcher. */
class InMemoryLedgerStore : LedgerStore {
    private val records = mutableListOf<PaymentRecord>()

    override fun all(): List<PaymentRecord> = records.toList()

    override fun hasCountedKey(dedupeKey: String): Boolean =
        records.any { it.isCounted() && it.dedupeKey == dedupeKey }

    override fun append(record: PaymentRecord) {
        records += record
    }

    override fun rewrite(records: List<PaymentRecord>) {
        this.records.clear()
        this.records += records
    }
}

/**
 * Port of `ledger.py`.
 *
 * The ledger is the shop's notebook. Every parsed SMS is one record. A record
 * whose dedupe key was already counted is marked duplicate, flagged for review
 * and never counted twice.
 */
class Ledger(
    private val store: LedgerStore = InMemoryLedgerStore(),
    /**
     * Cross-source window in seconds. Zero keeps the Python parity rules
     * unchanged; the app sets 120 seconds so a bank SMS and the matching
     * payment-app notification are counted once.
     */
    private val crossSourceWindowSeconds: Long = 0,
) {

    fun allRecords(): List<PaymentRecord> = store.all()

    /**
     * Store one record and return the stored copy.
     *
     * Only credited sales and refunds participate in dedupe. A repeat is kept
     * in the ledger for the audit trail, but marked [PaymentRecord.duplicate]
     * and [PaymentRecord.needsReview].
     *
     * With [crossSourceWindowSeconds] > 0, a credit/refund of the same amount
     * from a different source inside the window is treated as the same payment
     * when exactly one candidate exists and the references do not conflict.
     * Two candidates are not merged: both are flagged for review instead.
     */
    fun add(record: PaymentRecord): PaymentRecord {
        var stored = record
        if (record.status == PaymentStatus.CREDITED || record.status == PaymentStatus.REFUND) {
            if (store.hasCountedKey(record.dedupeKey)) {
                stored = record.copy(
                    duplicate = true,
                    needsReview = true,
                    reason = appendReason(record.reason, "duplicate"),
                )
            } else if (crossSourceWindowSeconds > 0 && record.isCounted()) {
                val candidates = store.all().filter { candidate ->
                    candidate.isCounted() &&
                        candidate.status == record.status &&
                        candidate.source != record.source &&
                        candidate.source != PaymentSource.UNKNOWN &&
                        record.source != PaymentSource.UNKNOWN &&
                        candidate.amountPaise == record.amountPaise &&
                        withinCrossSourceWindow(candidate, record) &&
                        !referencesConflict(candidate, record)
                }

                when (candidates.size) {
                    1 -> {
                        stored = record.copy(
                            duplicate = true,
                            needsReview = true,
                            dedupeUncertain = true,
                            reason = appendReason(record.reason, "cross_source_duplicate"),
                        )
                    }

                    in 2..Int.MAX_VALUE -> {
                        // Do not drop money on a guess. Keep the new record and
                        // mark every candidate so the stall can check the books.
                        stored = record.copy(
                            needsReview = true,
                            reason = appendReason(record.reason, "cross_source_ambiguous"),
                        )
                        store.rewrite(
                            store.all().map { existing ->
                                if (candidates.any { it == existing }) {
                                    existing.copy(
                                        needsReview = true,
                                        reason = appendReason(
                                            existing.reason,
                                            "cross_source_ambiguous",
                                        ),
                                    )
                                } else {
                                    existing
                                }
                            },
                        )
                    }
                }
            }
        }
        store.append(stored)
        return stored
    }

    /**
     * Correction path: remove the most recent counted credit.
     *
     * Refunds, excluded messages and duplicates are never removed by this
     * operation, matching the Python app's undo button.
     */
    fun undoLastSale(): PaymentRecord? {
        val records = store.all().toMutableList()
        val index = records.indexOfLast {
            it.status == PaymentStatus.CREDITED && !it.duplicate
        }
        if (index < 0) return null
        val removed = records.removeAt(index)
        store.rewrite(records)
        return removed
    }

    /** Records whose payment timestamp falls on [date] (local calendar day). */
    fun dayRecords(date: LocalDate = LocalDate.now()): List<PaymentRecord> {
        val prefix = date.toString()
        return store.all().filter { it.timestamp.startsWith(prefix) }
    }

    /** Stats for one day, using the same rules as `ledger.daily_stats`. */
    fun dailyStats(date: LocalDate = LocalDate.now()): DailyStats =
        LedgerStats.compute(dayRecords(date))

    private fun withinCrossSourceWindow(a: PaymentRecord, b: PaymentRecord): Boolean {
        val first = parseTimestamp(a.timestamp) ?: return false
        val second = parseTimestamp(b.timestamp) ?: return false
        return abs(Duration.between(first, second).seconds) <= crossSourceWindowSeconds
    }

    private fun referencesConflict(a: PaymentRecord, b: PaymentRecord): Boolean {
        val first = a.txnId?.trim().orEmpty()
        val second = b.txnId?.trim().orEmpty()
        return first.isNotEmpty() && second.isNotEmpty() && first != second
    }

    private fun parseTimestamp(value: String): LocalDateTime? = try {
        LocalDateTime.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun appendReason(reason: String, code: String): String = when {
        reason.isEmpty() -> code
        reason.split(';').contains(code) -> reason
        else -> "$reason;$code"
    }
}

/**
 * Totals over counted sales only. Duplicates, failed payments and other
 * excluded messages never appear in the money figures. The lists are kept so
 * the summary layer can name the refund/review counts without another query.
 */
data class DailyStats(
    val count: Int,
    val totalPaise: Long,
    val averagePaise: Long,
    val busiestHour: Int?,
    val largestPaise: Long,
    val refundCount: Int,
    val refundTotalPaise: Long,
    val reviewCount: Int,
    val duplicateCount: Int,
    val sales: List<PaymentRecord>,
    val refunds: List<PaymentRecord>,
    val review: List<PaymentRecord>,
)

object LedgerStats {

    fun compute(records: List<PaymentRecord>): DailyStats {
        val sales = records.filter {
            it.status == PaymentStatus.CREDITED && !it.duplicate
        }
        val refunds = records.filter {
            it.status == PaymentStatus.REFUND && !it.duplicate
        }
        val review = records.filter { it.needsReview && !it.duplicate }

        val total = sales.sumOf { it.amountPaise }
        val hours = LinkedHashMap<Int, Int>()
        for (sale in sales) {
            val hour = sale.paymentHour() ?: continue
            hours[hour] = (hours[hour] ?: 0) + 1
        }

        return DailyStats(
            count = sales.size,
            totalPaise = total,
            averagePaise = if (sales.isEmpty()) 0L else total / sales.size,
            busiestHour = hours.maxByOrNull { it.value }?.key,
            largestPaise = sales.maxOfOrNull { it.amountPaise } ?: 0L,
            refundCount = refunds.size,
            refundTotalPaise = refunds.sumOf { it.amountPaise },
            reviewCount = review.size,
            duplicateCount = records.count { it.duplicate },
            sales = sales,
            refunds = refunds,
            review = review,
        )
    }

    private fun PaymentRecord.paymentHour(): Int? = try {
        LocalDateTime.parse(timestamp).hour
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun PaymentRecord.isCounted(): Boolean =
    (status == PaymentStatus.CREDITED || status == PaymentStatus.REFUND) && !duplicate
