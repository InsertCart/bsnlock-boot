package com.emilock.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
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
 *  1. Launched by Provisioner ("from_provisioner") → activate our admin silently then finish
 *  2. Not Device Owner & not enrolled → show PermissionSetupActivity
 *  3. Device Owner but not enrolled → skip permissions, go to EnrollmentActivity
 *  4. Enrolled + locked → show LockScreenActivity
 *  5. Enrolled + unlocked → start MonitoringService silently and finish
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "EmiLock.Main"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val dpm   = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val prefs = PreferencesManager(this)

        // ── FIX #4: Provisioner launched us just to activate our DeviceAdminReceiver ──
        // transferOwnership() requires our admin to be ACTIVE before the call.
        // Provisioner passes this flag, we activate ourselves via the hidden setActiveAdmin API,
        // then finish back to Provisioner which can then transfer ownership.
        if (intent.getBooleanExtra("from_provisioner", false)) {
            Log.d(TAG, "Launched by Provisioner — activating DeviceAdminReceiver")
            activateAdminForTransfer(dpm)
            // Don't route anywhere — just finish so Provisioner's delay/poll can proceed
            finish()
            return
        }

        // Launched normally after ownership transfer
        if (dpm.isDeviceOwnerApp(packageName)) {
            Log.d(TAG, "Is Device Owner — applying restrictions")
            DeviceLockManager(this).applyDeviceOwnerRestrictions()
        } else {
            Log.w(TAG, "NOT Device Owner. Device must be provisioned.")
        }

        routeToEnrollmentOrLock(dpm, prefs)
    }

    /**
     * FIX #3: Activate our DeviceAdminReceiver so Provisioner can call transferOwnership().
     *
     * The target admin MUST be active before transferOwnership() is called.
     * As the target app, we can request our own admin activation using the hidden
     * DevicePolicyManager.setActiveAdmin() API via reflection (Provisioner is DO, so
     * the system allows this silently on enterprise provisioned devices).
     *
     * If reflection fails, we fall back to no-op — Provisioner's IllegalArgumentException
     * will show "Retry" which the dealer can tap once to complete.
     */
    private fun activateAdminForTransfer(dpm: DevicePolicyManager) {
        val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)

        // Already active — nothing to do
        if (dpm.isAdminActive(admin)) {
            Log.d(TAG, "Admin already active")
            return
        }

        // Try silent activation via reflection (works on enterprise-provisioned devices)
        try {
            val method = dpm.javaClass.getMethod("setActiveAdmin", ComponentName::class.java, Boolean::class.java)
            method.invoke(dpm, admin, true)
            Log.d(TAG, "✅ DeviceAdminReceiver activated via reflection")
        } catch (e: Exception) {
            Log.w(TAG, "Reflection activation failed (${e.message}) — Provisioner will need retry")
            // This is OK — Provisioner shows "Retry" and dealer taps once
        }
    }

    private fun routeToEnrollmentOrLock(dpm: DevicePolicyManager, prefs: PreferencesManager) {
        val isDeviceOwner = dpm.isDeviceOwnerApp(packageName)

        when {
            !prefs.isEnrolled -> {
                val dest = when {
                    // Device Owner: permissions were granted silently by Provisioner → skip setup
                    isDeviceOwner || prefs.permissionsGranted ->
                        Intent(this, EnrollmentActivity::class.java)
                    // Manual install: need to request permissions normally
                    else ->
                        Intent(this, PermissionSetupActivity::class.java)
                }
                startActivity(dest)
                finish()
            }

            prefs.isLocked -> {
                startActivity(Intent(this, LockScreenActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }

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