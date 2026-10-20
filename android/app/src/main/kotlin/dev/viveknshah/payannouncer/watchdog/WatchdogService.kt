package dev.viveknshah.payannouncer.watchdog

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import dev.viveknshah.payannouncer.BuildConfig
import dev.viveknshah.payannouncer.MainActivity
import dev.viveknshah.payannouncer.R
import dev.viveknshah.payannouncer.intake.NotificationIntakeService

/**
 * Small foreground watchdog. The NotificationListenerService is bound by the
 * system and works without this, but cheap OEM builds kill background
 * processes aggressively. The ongoing notification keeps the process alive
 * and gives the stall a visible "announcer is on" state.
 *
 * The service does not read notifications itself; it only keeps the intake
 * process resident.
 */
class WatchdogService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        running = true
        if (!isNeeded(this)) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isNeeded(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Payment listener",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the payment announcer ready"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_payment)
            .setContentTitle("Payment Announcer is on")
            .setContentText("Listening for payment alerts. Tap to open.")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "Watchdog"
        private const val CHANNEL_ID = "payment-watchdog"
        private const val NOTIFICATION_ID = 7

        @Volatile
        var running: Boolean = false
            private set

        /** Start only when at least one intake path has been granted. */
        fun startIfNeeded(context: Context) {
            if (!isNeeded(context)) return
            try {
                context.startForegroundService(
                    Intent(context, WatchdogService::class.java),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Could not start watchdog", e)
            }
        }

        fun isNeeded(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            val listener = ComponentName(context, NotificationIntakeService::class.java)
            val notificationAccess = enabled
                .split(':')
                .mapNotNull(ComponentName::unflattenFromString)
                .any { it == listener }

            val smsAccess = BuildConfig.FLAVOR == "foss" &&
                context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) ==
                PackageManager.PERMISSION_GRANTED

            return notificationAccess || smsAccess
        }
    }
}
