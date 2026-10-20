package dev.viveknshah.payannouncer.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the watchdog after a reboot or an app update. Android rebinds the
 * notification listener by itself; this only restores the foreground process
 * that OEM task killers are less likely to remove.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> WatchdogService.startIfNeeded(context)
        }
    }
}
