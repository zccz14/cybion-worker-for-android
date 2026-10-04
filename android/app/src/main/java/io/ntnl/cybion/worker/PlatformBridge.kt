package io.ntnl.cybion.worker

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
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
     * Hands the verified APK to the system package installer through a
     * FileProvider content URI. An empty return value means the installer was
     * engaged: its UI was opened directly, or the fallback notification
     * posted here opens it when the OS suppresses background activity starts.
     * The confirmation itself belongs to the system installer; the state
     * observed by the Rust core lives in [UpgradeInstall].
     */
    @JvmStatic
    fun installApk(path: String): String {
        val context = AppContext.context ?: return "the application context is unavailable"
        val file = File(path)
        if (!file.exists()) return "the verified APK is missing"
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            try {
                @Suppress("DEPRECATION")
                val targets = context.packageManager.queryIntentActivities(intent, 0)
                for (target in targets) {
                    context.grantUriPermission(
                        target.activityInfo.packageName,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            } catch (error: Exception) {
                Log.w(TAG, "could not pre-grant the APK URI", error)
            }
            UpgradeInstall.set("user_action")
            val opened = try {
                context.startActivity(intent)
                true
            } catch (error: Exception) {
                // Background activity starts can be suppressed; the
                // notification then carries the same intent.
                Log.w(TAG, "installer did not open directly", error)
                false
            }
            val notified = UpgradeInstall.notifyUser(context, intent)
            if (opened || notified) {
                ""
            } else {
                UpgradeInstall.set("failed:the system installer could not be opened")
                "the system installer could not be opened"
            }
        } catch (error: Exception) {
            Log.w(TAG, "installApk failed", error)
            val message = error.message ?: "the installer hand-off failed"
            UpgradeInstall.set("failed:$message")
            message
        }
    }

    @JvmStatic
    fun installState(): String = UpgradeInstall.state
}
