package io.ntnl.cybion.worker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Provides the device capabilities behind `computer_use`: gestures, text
 * input, screenshots, the UI tree, and global navigation keys.
 */
class WorkerAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "CybionAccessibility"
        private const val MAX_DEPTH = 40
        private const val TEXT_LIMIT = 200

        @Volatile
        var instance: WorkerAccessibilityService? = null
            private set
    }

    private val screenshotExecutor by lazy { Executors.newSingleThreadExecutor() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility service connected")
    }

    override fun onDestroy() {
        instance = null
        screenshotExecutor.shutdown()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    /** Runs one gesture stroke; equal start and end points produce a tap. */
    fun performGesture(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            durationMs.toLong().coerceAtLeast(1L),
        )
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(true)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }
        if (!dispatchGesture(gesture, callback, null)) return false
        return try {
            latch.await(5, TimeUnit.SECONDS) && completed.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** Writes text into the focused editable node. */
    fun typeText(text: String): Boolean {
        val focused = rootInActiveWindow
            ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false
        if (!focused.isEditable) return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /** Captures the display as a base64 PNG; empty string when unavailable. */
    fun screenshot(): String {
        val latch = CountDownLatch(1)
        val encoded = AtomicReference("")
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            result.hardwareBuffer,
                            result.colorSpace,
                        )
                        if (bitmap != null) {
                            val stream = ByteArrayOutputStream()
                            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                                encoded.set(
                                    Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                                )
                            }
                            bitmap.recycle()
                        }
                        result.hardwareBuffer.close()
                    } catch (error: Throwable) {
                        Log.w(TAG, "screenshot processing failed", error)
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "screenshot failed with code $errorCode")
                    latch.countDown()
                }
            },
        )
        return try {
            latch.await(10, TimeUnit.SECONDS)
            encoded.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            ""
        }
    }

    /** Returns a bounded JSON description of the on-screen nodes. */
    fun uiTree(maxNodes: Int): String {
        val root = rootInActiveWindow ?: return "{\"nodes\":[]}"
        val nodes = JSONArray()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        val visitLimit = maxNodes * 4
        while (queue.isNotEmpty() && nodes.length() < maxNodes && visited < visitLimit) {
            val (node, depth) = queue.removeFirst()
            visited++
            describeNode(node)?.let { nodes.put(it) }
            if (depth < MAX_DEPTH) {
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { queue.addLast(it to depth + 1) }
                }
            }
        }
        return JSONObject().apply {
            put("package", root.packageName ?: "")
            put("screen", screenSize())
            put("nodes", nodes)
        }.toString()
    }

    /** Maps key names to Android global navigation actions. */
    fun performGlobalAction(name: String): Boolean {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> GLOBAL_ACTION_POWER_DIALOG
            else -> return false
        }
        return performGlobalAction(action)
    }

    private fun describeNode(node: AccessibilityNodeInfo): JSONObject? {
        val text = node.text?.toString()?.take(TEXT_LIMIT)
        val description = node.contentDescription?.toString()?.take(TEXT_LIMIT)
        val actionable = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
        if (text.isNullOrBlank() && description.isNullOrBlank() && !actionable) return null
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        return JSONObject().apply {
            if (!text.isNullOrBlank()) put("text", text)
            if (!description.isNullOrBlank()) put("desc", description)
            put("class", node.className?.toString()?.substringAfterLast('.') ?: "")
            put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
            if (node.isClickable) put("clickable", true)
            if (node.isEditable) put("editable", true)
            if (node.isScrollable) put("scrollable", true)
            if (node.isCheckable) put("checked", node.isChecked)
            if (node.isFocused) put("focused", true)
        }
    }

    private fun screenSize(): String {
        val metrics = resources.displayMetrics
        return "${metrics.widthPixels},${metrics.heightPixels}"
    }
}
