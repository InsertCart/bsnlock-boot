package com.emilock.app

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.emilock.app.data.local.PreferencesManager
import com.emilock.app.service.MonitoringService
import com.emilock.app.ui.EnrollmentActivity
import com.emilock.app.ui.LockScreenActivity
import com.emilock.app.ui.PermissionSetupActivity

/**
 * MainActivity — entry point and routing hub.
 *
 * Decision tree on every launch:
 *  1. Not Device Owner → show warning (device must be provisioned first)
 *  2. Device Owner but not enrolled → go to PermissionSetupActivity → EnrollmentActivity
 *  3. Enrolled + locked → show LockScreenActivity
 *  4. Enrolled + unlocked → start MonitoringService silently and finish
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val dpm   = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val prefs = PreferencesManager(this)

        // Step 1 — Must be Device Owner for full functionality
        if (!dpm.isDeviceOwnerApp(packageName)) {
            Log.w("EmiLock", "NOT Device Owner. Device must be provisioned via ADB.")
            // Still allow enrollment flow if user opened app manually
            routeToEnrollmentOrLock(prefs)
            return
        }

        // Apply Device Owner hardening on every launch (idempotent)
        DeviceLockManager(this).applyDeviceOwnerRestrictions()

        routeToEnrollmentOrLock(prefs)
    }

    private fun routeToEnrollmentOrLock(prefs: PreferencesManager) {
        when {
            // Not enrolled → permissions → enrollment
            !prefs.isEnrolled -> {
                val dest = if (!prefs.permissionsGranted) {
                    Intent(this, PermissionSetupActivity::class.java)
                } else {
                    Intent(this, EnrollmentActivity::class.java)
                }
                startActivity(dest)
                finish()
            }

            // Enrolled + locked → lock screen
            prefs.isLocked -> {
                startActivity(Intent(this, LockScreenActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }

            // Enrolled + unlocked → start monitoring silently
            else -> {
                startMonitoringService()
                finish()
            }
        }
    }

    private fun startMonitoringService() {
        val intent = Intent(this, MonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}