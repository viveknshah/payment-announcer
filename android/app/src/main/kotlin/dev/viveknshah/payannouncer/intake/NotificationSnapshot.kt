package dev.viveknshah.payannouncer.intake

/**
 * The Android notification fields the intake needs. Keeping this separate from
 * [android.app.Notification] makes the mapping testable on the JVM.
 *
 * Android title/text fields are untrusted input; they never leave this object
 * except as parser input. Raw notification text is not stored.
 */
data class NotificationSnapshot(
    val packageName: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val postedAtMillis: Long,
    val groupSummary: Boolean = false,
) {
    /**
     * Notification text is a hint, not an SMS. Prefer the expanded big text and
     * keep the title because some apps put the amount there.
     */
    fun body(): String {
        val main = listOf(bigText, text)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
        return listOfNotNull(
            title?.trim()?.takeIf(String::isNotEmpty),
            main,
            subText?.trim()?.takeIf(String::isNotEmpty),
        ).distinct().joinToString(" ")
    }
}
