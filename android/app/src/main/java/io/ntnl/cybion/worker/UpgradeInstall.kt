package io.ntnl.cybion.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Install state observed by the Rust core while a controlled upgrade runs.
 * Values: `idle`, `user_action` while the system installer owns the flow, and
 * `failed:<message>` when the installer could not be engaged at all.
 *
 * Success is not observable from this process: the update replaces it, and
 * the restarted Worker reports the new version, from which the Controller
 * infers completion.
 */
object UpgradeInstall {
    private const val TAG = "CybionUpgrade"
    private const val CHANNEL_ID = "cybion_worker_upgrade"
    private const val NOTIFICATION_ID = 2

    @Volatile
    var state: String = "idle"

    fun set(value: String) {
        state = value
    }

    /**
     * Posts the fallback notification whose tap opens the system installer.
     * It is the reliable path when the OS suppresses activity starts issued
     * from a background service. Returns false when nothing could be posted.
     */
    fun notifyUser(context: Context, install: Intent): Boolean {
        return try {
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return false
            if (!manager.areNotificationsEnabled()) return false
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.upgrade_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
            val pending = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                install,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // The full-screen intent needs a special grant on Android 14+ and
            // can be rejected while posting; retry once without it so the
            // tap-to-install path still exists.
            var posted = false
            for (fullScreen in booleanArrayOf(true, false)) {
                try {
                    manager.notify(NOTIFICATION_ID, notification(context, pending, fullScreen))
                    posted = true
                    break
                } catch (error: Exception) {
                    Log.w(TAG, "upgrade notification attempt failed (fullScreen=$fullScreen)", error)
                }
            }
            posted
        } catch (error: Exception) {
            Log.w(TAG, "could not prepare the upgrade notification", error)
            false
        }
    }

    /** Removes a stale upgrade notification after the update completed. */
    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun notification(
        context: Context,
        pending: PendingIntent,
        fullScreen: Boolean,
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.upgrade_notification_title))
            .setContentText(context.getString(R.string.upgrade_notification_text))
            .setContentIntent(pending)
            .setAutoCancel(true)
        if (fullScreen) {
            builder.setFullScreenIntent(pending, true)
        }
        return builder.build()
    }
}
