package dev.viveknshah.payannouncer.parser

/**
 * Sender lists and thresholds used while classifying an SMS.
 *
 * This is a typed port of the relevant keys in the Python project's
 * `config.json`. Keep the values identical so the Python app and the Android
 * core produce the same records.
 *
 * Defaults are deliberately closed: an empty allowlist means every sender is
 * unknown and nothing can be counted. The app must load the stall's config.
 */
data class ParserConfig(
    val largeAmountThresholdPaise: Long = 200_000L,
    val bankSenders: List<String> = emptyList(),
    val paymentAppSenders: List<String> = emptyList(),
    val paytmBusinessSenders: List<String> = emptyList(),
    val senderAllowlist: List<String> = emptyList(),
) {
    companion object {
        /**
         * Mirrors `config.json` in the repository root. Fictional senders only;
         * a real deployment should load the stall's own file.
         */
        val CHAI_STALL: ParserConfig = ParserConfig(
            largeAmountThresholdPaise = 200_000L,
            bankSenders = listOf(
                "BOB", "BARB", "HDFC", "ICICI", "SBI", "AXIS",
                "KOTAK", "PNB", "CANARA", "UNION", "YESB",
            ),
            paymentAppSenders = listOf(
                "PAYTM", "PHONEPE", "GPAY", "GOOGLEPAY", "BHIM", "AMAZONPAY",
            ),
            paytmBusinessSenders = listOf("PAYTMB", "PAYTMNB"),
            senderAllowlist = listOf(
                "BOB", "BARB", "HDFC", "ICICI", "SBI", "AXIS",
                "KOTAK", "PNB", "CANARA", "UNION", "YESB",
                "PAYTM", "PHONEPE", "GPAY", "BHIM",
            ),
        )
    }
}
