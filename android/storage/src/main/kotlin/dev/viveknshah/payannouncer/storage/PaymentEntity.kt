package dev.viveknshah.payannouncer.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.viveknshah.payannouncer.parser.PaymentRecord

/**
 * SQLite row for one SMS. [rowId] is the insertion order, which is what the
 * ledger uses as its stable order (two messages can share an id).
 */
@Entity(
    tableName = "payments",
    indices = [
        Index(value = ["dedupe_key"]),
        Index(value = ["status", "duplicate"]),
    ],
)
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id")
    val rowId: Long = 0,
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "sender")
    val sender: String,
    @ColumnInfo(name = "amount_paise")
    val amountPaise: Long,
    @ColumnInfo(name = "payer")
    val payer: String?,
    @ColumnInfo(name = "txn_id")
    val txnId: String?,
    @ColumnInfo(name = "timestamp")
    val timestamp: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "needs_review")
    val needsReview: Boolean,
    @ColumnInfo(name = "large")
    val large: Boolean,
    @ColumnInfo(name = "duplicate")
    val duplicate: Boolean,
    @ColumnInfo(name = "dedupe_key")
    val dedupeKey: String,
    @ColumnInfo(name = "dedupe_uncertain")
    val dedupeUncertain: Boolean,
    @ColumnInfo(name = "reason")
    val reason: String,
    @ColumnInfo(name = "ingested_at")
    val ingestedAt: String,
)

internal fun PaymentEntity.toRecord(): PaymentRecord = PaymentRecord(
    id = id,
    sender = sender,
    amountPaise = amountPaise,
    payer = payer,
    txnId = txnId,
    timestamp = timestamp,
    status = status,
    source = source,
    needsReview = needsReview,
    large = large,
    duplicate = duplicate,
    dedupeKey = dedupeKey,
    dedupeUncertain = dedupeUncertain,
    reason = reason,
    ingestedAt = ingestedAt,
)

internal fun PaymentRecord.toEntity(): PaymentEntity = PaymentEntity(
    id = id,
    sender = sender,
    amountPaise = amountPaise,
    payer = payer,
    txnId = txnId,
    timestamp = timestamp,
    status = status,
    source = source,
    needsReview = needsReview,
    large = large,
    duplicate = duplicate,
    dedupeKey = dedupeKey,
    dedupeUncertain = dedupeUncertain,
    reason = reason,
    ingestedAt = ingestedAt,
)
