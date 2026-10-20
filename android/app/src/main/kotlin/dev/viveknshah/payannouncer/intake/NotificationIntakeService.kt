package dev.viveknshah.payannouncer.intake

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.viveknshah.payannouncer.AppGraph
import java.util.concurrent.Executors

/**
 * On-phone intake path. It ignores everything except the small package
 * allowlist in [NotificationIntake] and runs ledger work on one background
 * thread, then hands the stored record to the latest-wins Hindi speaker.
 */
class NotificationIntakeService : NotificationListenerService() {

    private val worker = Executors.newSingleThreadExecutor()
    private var intake: NotificationIntake? = null

    override fun onCreate() {
        super.onCreate()
        intake = try {
            NotificationIntake(AppGraph.ledger(this), AppGraph.config(this))
        } catch (e: Exception) {
            Log.e(TAG, "Could not start notification intake", e)
            null
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val snapshot = sbn?.toSnapshot() ?: return
        val processor = intake ?: return
        worker.execute {
            try {
                val record = processor.ingest(snapshot)
                if (record != null) AppGraph.speech(this).announce(record)
            } catch (e: Exception) {
                Log.e(TAG, "Notification intake failed", e)
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun StatusBarNotification.toSnapshot(): NotificationSnapshot? {
        val extras = notification.extras ?: return null
        return NotificationSnapshot(
            packageName = packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            postedAtMillis = postTime,
            groupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
        )
    }

    private companion object {
        const val TAG = "PaymentIntake"
    }
}
