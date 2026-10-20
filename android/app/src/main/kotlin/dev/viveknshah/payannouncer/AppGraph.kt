package dev.viveknshah.payannouncer

import android.content.Context
import dev.viveknshah.payannouncer.config.ConfigLoader
import dev.viveknshah.payannouncer.intake.PaymentIntake
import dev.viveknshah.payannouncer.ledger.Ledger
import dev.viveknshah.payannouncer.parser.ParserConfig
import dev.viveknshah.payannouncer.storage.LedgerDatabase
import dev.viveknshah.payannouncer.storage.RoomLedgerStore
import dev.viveknshah.payannouncer.voice.HindiTtsEngine

/**
 * One database per process. The notification service, SMS receiver and the
 * activity share it; Room handles their concurrent queries.
 */
object AppGraph {

    const val CROSS_SOURCE_WINDOW_SECONDS = 120L

    @Volatile
    private var database: LedgerDatabase? = null

    @Volatile
    private var parserConfig: ParserConfig? = null

    @Volatile
    private var speechEngine: HindiTtsEngine? = null

    fun store(context: Context): RoomLedgerStore =
        RoomLedgerStore(database(context).paymentDao())

    fun ledger(context: Context): Ledger = Ledger(
        store = store(context),
        crossSourceWindowSeconds = CROSS_SOURCE_WINDOW_SECONDS,
    )

    fun intake(context: Context): PaymentIntake = PaymentIntake(ledger(context), config(context))

    fun config(context: Context): ParserConfig {
        parserConfig?.let { return it }
        return synchronized(this) {
            parserConfig ?: ConfigLoader.load(
                context.applicationContext.assets.open("config.json"),
            ).also { parserConfig = it }
        }
    }

    fun speech(context: Context): HindiTtsEngine {
        speechEngine?.let { return it }
        return synchronized(this) {
            speechEngine ?: HindiTtsEngine(context.applicationContext)
                .also { speechEngine = it }
        }
    }

    private fun database(context: Context): LedgerDatabase {
        database?.let { return it }
        return synchronized(this) {
            database ?: LedgerDatabase.open(context.applicationContext)
                .also { database = it }
        }
    }
}
