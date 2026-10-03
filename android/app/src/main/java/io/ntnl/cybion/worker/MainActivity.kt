package io.ntnl.cybion.worker

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject

/** Single screen: the worker switch, live status, pairing, and permissions. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var switchEnabled: Switch
    private lateinit var textStatus: TextView
    private lateinit var groupPairing: View
    private lateinit var textPairingCode: TextView
    private lateinit var buttonOpenPairing: Button
    private lateinit var buttonRetry: Button
    private lateinit var buttonReset: Button
    private lateinit var textNotifications: TextView
    private lateinit var textAccessibility: TextView
    private lateinit var textBattery: TextView
    private lateinit var textFooter: TextView
    private var pairingUrl: String = ""

    private val poll = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContext.context = applicationContext
        setContentView(R.layout.activity_main)
        bindViews()

        switchEnabled.isChecked = Prefs.enabled(this)
        switchEnabled.setOnCheckedChangeListener { _, checked ->
            Prefs.setEnabled(this, checked)
            if (checked) {
                requestNotificationPermission()
                startForegroundService(
                    Intent(this, WorkerService::class.java).setAction(WorkerService.ACTION_START)
                )
            } else {
                startService(
                    Intent(this, WorkerService::class.java).setAction(WorkerService.ACTION_STOP)
                )
            }
        }

        buttonOpenPairing.setOnClickListener {
            if (pairingUrl.isNotEmpty()) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pairingUrl)))
            }
        }
        findViewById<Button>(R.id.button_notifications).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
        findViewById<Button>(R.id.button_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.button_battery).setOnClickListener {
            @Suppress("BatteryLife")
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
        }
        buttonRetry.setOnClickListener {
            // A single start request: the engine replaces a dead worker thread and
            // no-ops while one is already running. Stopping first would race the
            // service teardown against the restart and kill the fresh engine.
            startForegroundService(
                Intent(this, WorkerService::class.java).setAction(WorkerService.ACTION_START)
            )
        }
        buttonReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.reset_pairing)
                .setMessage(R.string.reset_pairing_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (!WorkerService.isRunning) {
                        WorkerCore.nativeReset(filesDir.absolutePath)
                        renderStatus()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        textFooter.text = getString(R.string.footer, BuildConfig.VERSION_NAME)
    }

    override fun onResume() {
        super.onResume()
        switchEnabled.isChecked = Prefs.enabled(this)
        // OEM killers (MIUI and friends) tear the service down aggressively. When
        // the user opens the app while the worker is enabled, bring it back.
        if (Prefs.enabled(this) && !WorkerService.isRunning) {
            startForegroundService(
                Intent(this, WorkerService::class.java).setAction(WorkerService.ACTION_START)
            )
        }
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    private fun bindViews() {
        switchEnabled = findViewById(R.id.switch_enabled)
        textStatus = findViewById(R.id.text_status)
        groupPairing = findViewById(R.id.group_pairing)
        textPairingCode = findViewById(R.id.text_pairing_code)
        buttonOpenPairing = findViewById(R.id.button_open_pairing)
        buttonRetry = findViewById(R.id.button_retry)
        buttonReset = findViewById(R.id.button_reset)
        textNotifications = findViewById(R.id.text_notifications)
        textAccessibility = findViewById(R.id.text_accessibility)
        textBattery = findViewById(R.id.text_battery)
        textFooter = findViewById(R.id.text_footer)
    }

    private fun renderStatus() {
        val snapshot = try {
            JSONObject(WorkerCore.nativeStatus())
        } catch (error: Throwable) {
            Log.e("CybionMain", "status read failed", error)
            textStatus.text = getString(R.string.status_native_error, error.message ?: "")
            return
        }
        val phase = snapshot.optJSONObject("phase") ?: JSONObject()
        val name = phase.optString("name", "stopped")
        val lines = mutableListOf<String>()
        lines += when (name) {
            "starting" -> getString(R.string.phase_starting)
            "pairing" -> getString(R.string.phase_pairing)
            "connecting" -> getString(R.string.phase_connecting)
            "online" -> getString(R.string.phase_online)
            "reconnecting" -> getString(R.string.phase_reconnecting, phase.optInt("attempt", 0))
            "failed" -> getString(R.string.phase_failed, phase.optString("message", ""))
            else -> getString(R.string.phase_stopped)
        }
        snapshot.nullableString("machine_id")
            ?.let { lines += getString(R.string.status_worker_id, it) }
        snapshot.nullableString("hostname")
            ?.let { lines += getString(R.string.status_hostname, it) }
        textStatus.text = lines.joinToString("\n")

        val pairing = name == "pairing"
        groupPairing.visibility = if (pairing) View.VISIBLE else View.GONE
        if (pairing) {
            textPairingCode.text = phase.optString("user_code")
            pairingUrl = phase.optString("url")
        }
        buttonRetry.visibility = if (name == "failed") View.VISIBLE else View.GONE
        buttonReset.visibility = if (name == "stopped" && !WorkerService.isRunning) {
            View.VISIBLE
        } else {
            View.GONE
        }

        val notificationsGranted = if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        textNotifications.text = getString(
            if (notificationsGranted) R.string.permission_granted else R.string.permission_missing
        )
        val accessibilityEnabled = WorkerAccessibilityService.instance != null
        textAccessibility.text = getString(
            if (accessibilityEnabled) R.string.permission_granted else R.string.permission_missing
        )
        val powerManager = getSystemService(PowerManager::class.java)
        val batteryExempt = powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        textBattery.text = getString(
            if (batteryExempt) R.string.permission_granted else R.string.permission_missing
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}

/** `optString` renders JSON null as the string "null"; return a real null instead. */
private fun JSONObject.nullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
