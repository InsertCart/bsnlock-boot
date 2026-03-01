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
 * MainActivity — routing hub only. No UI.
 *
 * Decision tree on every launch:
 *  1. Not enrolled, not DO, no permissions → PermissionSetupActivity
 *     (dealer grants permissions + activates Device Admin, then goes to TestDPC)
 *  2. Not enrolled, IS Device Owner (transfer just happened) → EnrollmentActivity
 *     (onTransferOwnershipComplete already set permissionsGranted = true)
 *  3. Enrolled + locked → LockScreenActivity
 *  4. Enrolled + unlocked → start MonitoringService silently and finish
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "EmiLock.Main"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ─── FIX BUG #3: No setContentView here ──────────────────────────────
        // This is a routing-only activity. Loading the blank layout caused a
        // white screen flash before routing. We route immediately in onCreate.

        val dpm   = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val prefs = PreferencesManager(this)

        // ─── FIX BUG #3: Removed dead from_provisioner block ─────────────────
        // TestDPC never launches EmiLock with from_provisioner=true.
        // Device Admin activation is done by the dealer in PermissionSetupActivity
        // via the standard ACTION_ADD_DEVICE_ADMIN dialog. No reflection needed.

        // If we are Device Owner, apply restrictions immediately on every launch.
        // This is a no-op if already applied; safe to call repeatedly.
        if (dpm.isDeviceOwnerApp(packageName)) {
            Log.d(TAG, "Is Device Owner — ensuring restrictions are applied")
            DeviceLockManager(this).applyDeviceOwnerRestrictions()
        } else {
            Log.d(TAG, "Not Device Owner yet — waiting for TestDPC transfer")
        }

        routeToCorrectScreen(dpm, prefs)
    }

    private fun routeToCorrectScreen(dpm: DevicePolicyManager, prefs: PreferencesManager) {
        val isDeviceOwner = dpm.isDeviceOwnerApp(packageName)

        val destination: Intent = when {

            // ── Not yet enrolled ─────────────────────────────────────────────
            !prefs.isEnrolled -> {
                when {
                    // Transfer just completed: we're DO and permissions were granted
                    // silently in onTransferOwnershipComplete → go straight to enrollment
                    isDeviceOwner || prefs.permissionsGranted -> {
                        Log.d(TAG, "DO transfer complete → EnrollmentActivity")
                        Intent(this, EnrollmentActivity::class.java)
                    }
                    // First launch: dealer needs to grant permissions + activate Device Admin
                    // THEN go to TestDPC for transfer. PermissionSetupActivity handles this.
                    else -> {
                        Log.d(TAG, "Not enrolled, not DO → PermissionSetupActivity")
                        Intent(this, PermissionSetupActivity::class.java)
                    }
                }
            }

            // ── Enrolled + locked ────────────────────────────────────────────
            prefs.isLocked -> {
                Log.d(TAG, "Enrolled + locked → LockScreenActivity")
                Intent(this, LockScreenActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            }

            // ── Enrolled + unlocked → just ensure service is running ─────────
            else -> {
                Log.d(TAG, "Enrolled + unlocked → starting MonitoringService")
                startMonitoringService()
                finish()
                return
            }
        }

        startActivity(destination)
        finish()
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