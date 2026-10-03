package io.ntnl.cybion.worker

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest

/** Static JNI surface called from the Rust core. */
object PlatformBridge {
    private const val TAG = "CybionPlatform"

    @JvmStatic
    fun gesture(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean =
        WorkerAccessibilityService.instance?.performGesture(x1, y1, x2, y2, durationMs) ?: false

    @JvmStatic
    fun typeText(text: String): Boolean =
        WorkerAccessibilityService.instance?.typeText(text) ?: false

    @JvmStatic
    fun screenshot(): String =
        WorkerAccessibilityService.instance?.screenshot() ?: ""

    @JvmStatic
    fun uiTree(maxNodes: Int): String =
        WorkerAccessibilityService.instance?.uiTree(maxNodes) ?: "{\"nodes\":[]}"

    @JvmStatic
    fun launchApp(packageName: String): Boolean {
        val context = AppContext.context ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (error: Exception) {
            Log.w(TAG, "launchApp failed for $packageName", error)
            false
        }
    }

    @JvmStatic
    fun openUrl(url: String): Boolean {
        val context = AppContext.context ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (error: Exception) {
            Log.w(TAG, "openUrl failed for $url", error)
            false
        }
    }

    @JvmStatic
    fun globalAction(name: String): Boolean =
        WorkerAccessibilityService.instance?.performGlobalAction(name) ?: false

    @JvmStatic
    fun screenSize(): String {
        val context = AppContext.context ?: WorkerAccessibilityService.instance ?: return ""
        val metrics = context.resources.displayMetrics
        return "${metrics.widthPixels},${metrics.heightPixels}"
    }

    @JvmStatic
    fun accessibilityEnabled(): Boolean = WorkerAccessibilityService.instance != null

    /** SHA-256 of the APK signing certificate (lowercase hex), or "" when unreadable. */
    @JvmStatic
    fun apkSigner(path: String): String {
        val context = AppContext.context ?: return ""
        return try {
            val info = context.packageManager.getPackageArchiveInfo(
                path,
                PackageManager.GET_SIGNING_CERTIFICATES,
            ) ?: return ""
            val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return ""
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
                .joinToString("") { "%02x".format(it) }
        } catch (error: Exception) {
            Log.w(TAG, "apkSigner failed for $path", error)
            ""
        }
    }

    @JvmStatic
    fun apkVersion(path: String): String {
        val context = AppContext.context ?: return ""
        return try {
            context.packageManager.getPackageArchiveInfo(path, 0)?.versionName ?: ""
        } catch (error: Exception) {
            Log.w(TAG, "apkVersion failed for $path", error)
            ""
        }
    }

    /**
     * Streams the verified APK into a package-installer session. An empty
     * return value means the session was committed; the system then shows its
     * own confirmation prompt and reports the result to [InstallResultReceiver].
     */
    @JvmStatic
    fun installApk(path: String): String {
        val context = AppContext.context ?: return "the application context is unavailable"
        val file = File(path)
        if (!file.exists()) return "the verified APK is missing"
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("cybion-worker.apk", 0, file.length).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val mutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                val result = Intent(context, InstallResultReceiver::class.java)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    result,
                    PendingIntent.FLAG_UPDATE_CURRENT or mutability,
                )
                UpgradeInstall.set("pending")
                session.commit(pending.intentSender)
            }
            ""
        } catch (error: Exception) {
            Log.w(TAG, "installApk failed", error)
            val message = error.message ?: "the install session failed"
            UpgradeInstall.set("failed:$message")
            message
        }
    }

    @JvmStatic
    fun installState(): String = UpgradeInstall.state
}
