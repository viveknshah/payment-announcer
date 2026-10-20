package dev.viveknshah.payannouncer.storage

import android.content.Context
import dev.viveknshah.payannouncer.ledger.LedgerStore
import dev.viveknshah.payannouncer.parser.PaymentRecord

/**
 * Room-backed [LedgerStore]. Dedupe and stats rules stay in `:ledger`; this
 * class only moves records in and out of SQLite.
 *
 * All methods are blocking. Call them from `Dispatchers.IO` (or a Room
 * transaction executor), not from the main thread.
 */
class RoomLedgerStore(
    private val dao: PaymentDao,
) : LedgerStore {

    override fun all(): List<PaymentRecord> = dao.all().map(PaymentEntity::toRecord)

    override fun hasCountedKey(dedupeKey: String): Boolean =
        dao.hasCountedKey(dedupeKey)

    override fun append(record: PaymentRecord) {
        dao.insert(record.toEntity())
    }

    override fun rewrite(records: List<PaymentRecord>) {
        dao.replaceAll(records.map(PaymentRecord::toEntity))
    }

    companion object {
        /** Convenience for the app's single ledger database. */
        fun open(context: Context): RoomLedgerStore =
            RoomLedgerStore(LedgerDatabase.open(context).paymentDao())
    }
}
