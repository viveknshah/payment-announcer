package dev.viveknshah.payannouncer.config

import dev.viveknshah.payannouncer.parser.ParserConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets

class ConfigLoaderTest {

    @Test
    fun bundledConfigMatchesTheDemoValues() {
        val file = File("../../config.json")
        assumeTrue("config.json not found; run from android/core", file.exists())

        assertEquals(
            ParserConfig.CHAI_STALL,
            ConfigLoader.parse(file.readText(StandardCharsets.UTF_8)),
        )
    }

    @Test
    fun missingKeysStayClosed() {
        val config = ConfigLoader.parse("{}")

        assertEquals(200_000L, config.largeAmountThresholdPaise)
        assertEquals(emptyList<String>(), config.bankSenders)
        assertEquals(emptyList<String>(), config.paymentAppSenders)
        assertEquals(emptyList<String>(), config.paytmBusinessSenders)
        assertEquals(emptyList<String>(), config.senderAllowlist)
    }

    @Test
    fun senderTokensAreTrimmedUppercasedAndDeduplicated() {
        val config = ConfigLoader.parse(
            """
            {
              "bank_senders": [" bob ", "HDFC", "", "bob"],
              "payment_app_senders": ["phonepe"],
              "paytm_business_senders": ["paytmb"],
              "sender_allowlist": ["bob", "PHONEPE"]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("BOB", "HDFC"), config.bankSenders)
        assertEquals(listOf("PHONEPE"), config.paymentAppSenders)
        assertEquals(listOf("PAYTMB"), config.paytmBusinessSenders)
        assertEquals(listOf("BOB", "PHONEPE"), config.senderAllowlist)
    }

    @Test
    fun wildcardAllowlistSurvivesNormalisation() {
        val config = ConfigLoader.parse("""{"sender_allowlist":[" * "]}""")

        assertEquals(listOf("*"), config.senderAllowlist)
    }

    @Test
    fun negativeThresholdIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ConfigLoader.parse("""{"large_amount_threshold_paise":-1}""")
        }
        assertTrue(error.message!!.contains("zero or positive"))
    }

    @Test
    fun malformedJsonIsRejectedWithAUsefulMessage() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ConfigLoader.parse("""{"bank_senders": [""")
        }
        assertTrue(error.message!!.startsWith("invalid payment config:"))
    }

    @Test
    fun loadsUtf8StreamAndClosesIt() {
        val bytes = """{"large_amount_threshold_paise":50000}"""
            .toByteArray(StandardCharsets.UTF_8)
        var closed = false
        val input = object : ByteArrayInputStream(bytes) {
            override fun close() {
                closed = true
                super.close()
            }
        }

        val config = ConfigLoader.load(input)

        assertEquals(50_000L, config.largeAmountThresholdPaise)
        assertTrue(closed)
    }
}
