package dev.viveknshah.payannouncer.parser

/** Final classification of one incoming SMS. */
object PaymentStatus {
    const val CREDITED = "credited"
    const val REFUND = "refund"
    const val EXCLUDED = "excluded"
    const val REVIEW = "review"
}

/** Where the message came from, based on the sender id. */
object PaymentSource {
    const val BANK_SMS = "bank_sms"
    const val PAYMENT_APP = "payment_app"
    const val PAYTM_BUSINESS = "paytm_business"
    const val UNKNOWN = "unknown"
}

/**
 * One normalised SMS. Money is always in paise; never a floating-point value.
 *
 * Field names mirror the Python dataclass so both implementations can be
 * compared record by record. The JSON adapter belongs to the app layer; this
 * module stays free of Android and JSON dependencies.
 */
data class PaymentRecord(
    val id: String,
    val sender: String,
    val amountPaise: Long,
    val payer: String?,
    val txnId: String?,
    /** ISO local date-time of the payment, from the SMS when it has one. */
    val timestamp: String,
    /** One of [PaymentStatus]. */
    val status: String,
    /** One of [PaymentSource]. */
    val source: String,
    val needsReview: Boolean,
    val large: Boolean,
    val duplicate: Boolean,
    val dedupeKey: String,
    val dedupeUncertain: Boolean,
    /** Semicolon-separated reason codes; empty for a clean credit. */
    val reason: String,
    val ingestedAt: String,
)
