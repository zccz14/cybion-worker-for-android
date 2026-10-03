package io.ntnl.cybion.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the worker after a reboot - and after a controlled self-upgrade -
 * when the user kept it enabled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs.enabled(context)) return
        try {
            context.startForegroundService(
                Intent(context, WorkerService::class.java).setAction(WorkerService.ACTION_START)
            )
        } catch (error: Exception) {
            Log.w("CybionBootReceiver", "could not restart the worker after boot", error)
        }
    }
}
