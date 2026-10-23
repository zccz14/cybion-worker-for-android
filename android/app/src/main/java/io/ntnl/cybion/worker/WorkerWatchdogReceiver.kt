package io.ntnl.cybion.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the worker back when the platform or the OEM dropped the foreground
 * service while the user still had it enabled. `START_STICKY` covers the plain
 * "low memory" case, but aggressive OEM builds (MIUI/HyperOS among them) stop
 * the service without ever restarting it, so a periodic check is the only
 * thing left.
 */
class WorkerWatchdogReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CybionWatchdog"

        /** Inexact on purpose: batching is fine, we only need "eventually back". */
        private const val INTERVAL_MS = 15 * 60 * 1000L

        fun schedule(context: Context) {
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            alarm.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                INTERVAL_MS,
                INTERVAL_MS,
                intent(context),
            )
        }

        fun cancel(context: Context) {
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            alarm.cancel(intent(context))
        }

        private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WorkerWatchdogReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.enabled(context)) {
            cancel(context)
            return
        }
        if (WorkerService.isRunning) return
        Log.i(TAG, "worker is not running; restarting it")
        try {
            context.startForegroundService(
                Intent(context, WorkerService::class.java).setAction(WorkerService.ACTION_START)
            )
        } catch (error: Exception) {
            Log.w(TAG, "could not restart the worker", error)
        }
    }
}
