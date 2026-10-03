package io.ntnl.cybion.worker

import android.content.Intent
import android.net.Uri
import android.util.Log

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
}
