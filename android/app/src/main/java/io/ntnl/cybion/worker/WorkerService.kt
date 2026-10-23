package io.ntnl.cybion.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject

/**
 * Foreground service hosting the Rust worker engine. The notification is the
 * user-visible proof that the phone is currently accepting remote tasks.
 */
class WorkerService : Service() {
    companion object {
        private const val TAG = "CybionWorkerService"
        const val ACTION_START = "io.ntnl.cybion.worker.action.START"
        const val ACTION_STOP = "io.ntnl.cybion.worker.action.STOP"
        const val CHANNEL_ID = "cybion_worker_status"
        private const val NOTIFICATION_ID = 1
        private const val WAKELOCK_TAG = "CybionWorker:wakelock"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        private var wakeLock: PowerManager.WakeLock? = null
    }

    override fun onCreate() {
        super.onCreate()
        AppContext.context = applicationContext
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopWorker()
                return START_NOT_STICKY
            }

            else -> {
                if (!Prefs.enabled(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startWorker()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        WorkerCore.nativeStop()
        releaseWakeLock()
        isRunning = false
        super.onDestroy()
    }

    /**
     * Swiping the task away stops the service on several OEM builds even though
     * it is sticky. Re-arm it right away while the user still has it enabled.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (Prefs.enabled(this)) {
            try {
                startForegroundService(
                    Intent(this, WorkerService::class.java).setAction(ACTION_START)
                )
            } catch (error: Exception) {
                Log.w(TAG, "could not re-arm the worker after the task was removed", error)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startWorker() {
        startForegroundCompat()
        acquireWakeLock()
        WorkerWatchdogReceiver.schedule(this)
        if (isRunning) return
        val started = try {
            WorkerCore.nativeStart(filesDir.absolutePath, deviceInfoJson())
        } catch (error: Throwable) {
            Log.e(TAG, "native start failed", error)
            false
        }
        isRunning = started
        if (!started) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopWorker() {
        Prefs.setEnabled(this, false)
        WorkerCore.nativeStop()
        isRunning = false
        WorkerWatchdogReceiver.cancel(this)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * A foreground service keeps the app visible but does not keep the CPU
     * awake: with the screen off the kernel may suspend timers and sockets,
     * which surfaces as late heartbeats and a dropped event stream - i.e. the
     * Worker reading as offline. Hold a partial lock for the whole run.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
    }

    private fun startForegroundCompat() {
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
    }

    private fun deviceInfoJson(): String = JSONObject().apply {
        put("hostname", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        put(
            "platform",
            "android ${Build.VERSION.RELEASE} (${Build.SUPPORTED_ABIS.firstOrNull() ?: ""})",
        )
        put("version", BuildConfig.VERSION_NAME)
        put("sdk", Build.VERSION.SDK_INT)
    }.toString()

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WorkerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.notification_stop),
                    stopIntent,
                ).build()
            )
            .build()
    }
}
