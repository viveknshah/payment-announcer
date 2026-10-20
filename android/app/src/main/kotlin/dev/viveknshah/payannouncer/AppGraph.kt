package dev.viveknshah.payannouncer

import android.content.Context
import dev.viveknshah.payannouncer.ledger.Ledger
import dev.viveknshah.payannouncer.storage.LedgerDatabase
import dev.viveknshah.payannouncer.storage.RoomLedgerStore

/**
 * One database per process. The notification service and the activity share it;
 * Room handles their concurrent queries.
 */
object AppGraph {

    @Volatile
    private var database: LedgerDatabase? = null

    fun store(context: Context): RoomLedgerStore =
        RoomLedgerStore(database(context).paymentDao())

    fun ledger(context: Context): Ledger = Ledger(store(context))

    private fun database(context: Context): LedgerDatabase {
        database?.let { return it }
        return synchronized(this) {
            database ?: LedgerDatabase.open(context.applicationContext)
                .also { database = it }
        }
    }
}
