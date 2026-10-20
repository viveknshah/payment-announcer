package dev.viveknshah.payannouncer.config

import dev.viveknshah.payannouncer.parser.ParserConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.Locale

/**
 * Reads the typed part of `config.json`.
 *
 * The Android app calls [load] with `assets.open("config.json")`; tests call
 * [parse] with a string. This module does not know about Android.
 *
 * Unknown keys (the Python app's `ollama`, `piper`, `ledger_file`) are ignored.
 * Missing sender lists stay empty, which is the parser's fail-closed default.
 * Sender tokens are trimmed, uppercased and de-duplicated so a hand-edited
 * config behaves the same as the bundled one.
 */
object ConfigLoader {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    /** Parse UTF-8 JSON and return the parser config. */
    fun parse(text: String): ParserConfig {
        val dto = try {
            json.decodeFromString<ConfigDto>(text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("invalid payment config: ${e.message}", e)
        }

        require(dto.largeAmountThresholdPaise >= 0) {
            "large_amount_threshold_paise must be zero or positive"
        }

        return ParserConfig(
            largeAmountThresholdPaise = dto.largeAmountThresholdPaise,
            bankSenders = dto.bankSenders.tokens(),
            paymentAppSenders = dto.paymentAppSenders.tokens(),
            paytmBusinessSenders = dto.paytmBusinessSenders.tokens(),
            senderAllowlist = dto.senderAllowlist.tokens(),
        )
    }

    /** Read a UTF-8 config stream and close it. */
    fun load(input: InputStream): ParserConfig =
        input.bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }

    private fun List<String>.tokens(): List<String> =
        map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()

    @Serializable
    private data class ConfigDto(
        @SerialName("large_amount_threshold_paise")
        val largeAmountThresholdPaise: Long = 200_000L,
        @SerialName("bank_senders")
        val bankSenders: List<String> = emptyList(),
        @SerialName("payment_app_senders")
        val paymentAppSenders: List<String> = emptyList(),
        @SerialName("paytm_business_senders")
        val paytmBusinessSenders: List<String> = emptyList(),
        @SerialName("sender_allowlist")
        val senderAllowlist: List<String> = emptyList(),
    )
}
