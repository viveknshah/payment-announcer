package dev.viveknshah.payannouncer.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface PaymentDao {

    @Query("SELECT * FROM payments ORDER BY row_id ASC")
    fun all(): List<PaymentEntity>

    /**
     * Same predicate as `Ledger`/`ledger.py`: only a non-duplicate credit or
     * refund makes a dedupe key counted.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM payments
            WHERE dedupe_key = :dedupeKey
              AND duplicate = 0
              AND status IN ('credited', 'refund')
        )
        """,
    )
    fun hasCountedKey(dedupeKey: String): Boolean

    @Insert
    fun insert(record: PaymentEntity): Long

    @Insert
    fun insertAll(records: List<PaymentEntity>)

    @Query("DELETE FROM payments")
    fun deleteAll()

    /** Used by undo: replace the table while keeping the caller's order. */
    @Transaction
    fun replaceAll(records: List<PaymentEntity>) {
        deleteAll()
        insertAll(records)
    }
}
