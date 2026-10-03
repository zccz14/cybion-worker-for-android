package io.ntnl.cybion.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restarts the worker after a reboot when the user kept it enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
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
