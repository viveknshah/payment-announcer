package dev.viveknshah.payannouncer.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PaymentEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun paymentDao(): PaymentDao

    companion object {
        /** Open (or create) the on-device ledger. Call from a background dispatcher. */
        fun open(context: Context, name: String = "ledger.db"): LedgerDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                LedgerDatabase::class.java,
                name,
            ).build()
    }
}
