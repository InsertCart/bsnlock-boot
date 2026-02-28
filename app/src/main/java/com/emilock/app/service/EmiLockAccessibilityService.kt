package com.emilock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.emilock.app.data.api.ApiClient
import com.emilock.app.data.local.PreferencesManager
import com.emilock.app.data.model.NotificationRequest
import com.emilock.app.ui.LockScreenActivity
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*

/**
 * EmiLockAccessibilityService
 *
 * Anti-tamper layer. While the device is enrolled:
 *  1. Blocks access to factory-reset, device-admin, and app-specific Settings pages.
 *  2. While LOCKED, re-launches LockScreenActivity whenever a foreign app comes to foreground.
 *  3. Sends a tamper alert to the dashboard after 2 blocked attempts.
 */
class EmiLockAccessibilityService : AccessibilityService() {

    private lateinit var prefs: PreferencesManager
    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tamperCount = 0

    companion object {
        const val ACTION_DISMISS_SYSTEM_UI = "com.emilock.app.ACTION_DISMISS_SYSTEM_UI"
        private const val TAG = "EmiLock.A11y"

        private val SENSITIVE_PACKAGES = setOf(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.miui.securitycenter", "com.miui.home", "com.miui.permcenter",
            "com.miui.packageinstaller", "com.miui.security",
            "com.samsung.android.settings", "com.samsung.android.lool",
            "com.oppo.settings", "com.coloros.settings", "com.coloros.safecenter",
            "com.realme.safecenter", "com.vivo.settings", "com.vivo.permissionmanager",
            "com.oneplus.settings"
        )

        private val GLOBAL_BLOCKED = listOf(
            "erase all data", "factory data reset", "factory reset", "reset device",
            "reset phone", "reset options",
            "device admin apps", "phone administrators", "device administrators"
        )

        private val APP_SPECIFIC_BLOCKED = listOf(
            "force stop", "uninstall", "disable", "clear storage", "clear data",
            "clear cache", "permissions", "deactivate", "deactivate & uninstall",
            "accessibility", "use service", "display over other apps",
            "battery", "usage access", "special app access", "install unknown apps"
        )

        // Our app identifier in Settings
        private val OUR_APP_LABELS = listOf("emilock", "emi lock", "emi manager")
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = PreferencesManager(this)
        val filter = IntentFilter(ACTION_DISMISS_SYSTEM_UI)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(commandReceiver, filter)
        }
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ─── Event handling ──────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!prefs.isEnrolled) return
        val pkg = event?.packageName?.toString() ?: return

        // ── LOCKED mode: force every foreign app back to lock screen
        if (prefs.isLocked) {
            if (pkg != this.packageName && pkg != "com.android.systemui") {
                launchLockScreen()
            } else if (pkg == "com.android.systemui") {
                performGlobalAction(GLOBAL_ACTION_BACK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                }
                launchLockScreen()
            }
            return
        }

        // ── UNLOCKED mode: anti-tamper only inside Settings packages
        if (SENSITIVE_PACKAGES.any { pkg.startsWith(it) }) {
            checkAndBlockTampering()
        }
    }

    // ─── Anti-tamper logic ───────────────────────────────────────────────────

    private fun checkAndBlockTampering() {
        val root = rootInActiveWindow ?: return
        val sb   = StringBuilder()
        collectText(root, sb)
        val screen = sb.toString().lowercase()

        // 1. Always-blocked keywords (e.g. factory reset screens)
        for (kw in GLOBAL_BLOCKED) {
            if (screen.contains(kw)) {
                Log.d(TAG, "GLOBAL block: '$kw'")
                block()
                return
            }
        }

        // 2. App-specific blocks — only when our app name is visible on screen
        val ourAppVisible = OUR_APP_LABELS.any { screen.contains(it) }
        if (ourAppVisible) {
            for (kw in APP_SPECIFIC_BLOCKED) {
                if (screen.contains(kw)) {
                    Log.d(TAG, "APP-SPECIFIC block: '$kw'")
                    block()
                    return
                }
            }
        }
    }

    private fun block() {
        Toast.makeText(this, "⛔ Access Denied: Protected by EmiLock", Toast.LENGTH_SHORT).show()
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_BACK)

        tamperCount++
        if (tamperCount >= 2) {
            tamperCount = 0
            sendTamperAlert()
        }
    }

    private fun sendTamperAlert() {
        val token = prefs.deviceToken ?: return
        val fused = LocationServices.getFusedLocationProviderClient(this)
        try {
            fused.lastLocation.addOnSuccessListener { loc ->
                scope.launch {
                    try {
                        ApiClient.api.sendNotification(
                            "Bearer $token",
                            NotificationRequest(
                                message   = "User attempting to tamper with EmiLock app",
                                latitude  = loc?.latitude  ?: 0.0,
                                longitude = loc?.longitude ?: 0.0,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Tamper alert error: ${e.message}")
                    }
                }
            }
        } catch (_: SecurityException) {}
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun launchLockScreen() {
        val intent = Intent(this, LockScreenActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(intent)
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, sb)
            child.recycle()
        }
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_DISMISS_SYSTEM_UI) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                }
            }
        }
    }
}