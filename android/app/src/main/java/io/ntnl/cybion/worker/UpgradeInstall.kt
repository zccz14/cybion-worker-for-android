package io.ntnl.cybion.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Install state observed by the Rust core while a controlled upgrade runs.
 * Values: `idle`, `pending`, `user_action`, `success`, `cancelled`,
 * `failed:<message>`.
 */
object UpgradeInstall {
    @Volatile
    var state: String = "idle"

    fun set(value: String) {
        state = value
    }
}

/** Receives the result of the package-installer session for the APK upgrade. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    UpgradeInstall.set("failed:the system installer provided no confirmation prompt")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                UpgradeInstall.set("user_action")
                try {
                    context.startActivity(confirm)
                } catch (error: Exception) {
                    Log.w("CybionUpgrade", "could not show the install prompt", error)
                    UpgradeInstall.set(
                        "failed:" + (error.message ?: "could not show the install prompt")
                    )
                }
            }
            PackageInstaller.STATUS_SUCCESS -> UpgradeInstall.set("success")
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpgradeInstall.set("cancelled")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                UpgradeInstall.set("failed:" + (message ?: "install failed with status $status"))
            }
        }
    }
}
