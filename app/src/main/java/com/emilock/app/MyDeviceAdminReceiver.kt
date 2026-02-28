package com.emilock.app

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import com.emilock.app.data.local.PreferencesManager

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "EmiLock.AdminReceiver"

        fun getComponentName(context: Context) =
            ComponentName(context, MyDeviceAdminReceiver::class.java)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "Device Admin ENABLED")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? {
        val prefs = PreferencesManager(context)
        return if (prefs.isEnrolled) {
            "WARNING: This device is protected by EmiLock. Disabling this administrator will violate your EMI agreement."
        } else null
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d(TAG, "Device Admin DISABLED")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.d(TAG, "Profile provisioning complete — applying hardening")
        DeviceLockManager(context).applyDeviceOwnerRestrictions()
    }

    /**
     * FIX #2 & #5: Called when Provisioner transfers Device Owner to EmiLock.
     *
     * At this point EmiLock IS the Device Owner. We must:
     * 1. Apply DO restrictions immediately
     * 2. Grant our own permissions silently (as new DO)
     * 3. Mark permissionsGranted = true so MainActivity skips PermissionSetupActivity
     * 4. Launch MainActivity so the user lands on EnrollmentActivity
     */
    override fun onTransferOwnershipComplete(context: Context, bundle: PersistableBundle?) {
        super.onTransferOwnershipComplete(context, bundle)
        Log.d(TAG, "✅ OWNERSHIP TRANSFER COMPLETE — EmiLock is now Device Owner!")

        val dpm   = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = getComponentName(context)
        val prefs = PreferencesManager(context)

        // Step 1: Apply Device Owner restrictions immediately
        DeviceLockManager(context).applyDeviceOwnerRestrictions()

        // Step 2: Grant all permissions to ourselves silently (we're now DO)
        val permissions = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_PHONE_NUMBERS,
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.CAMERA,
        )
        var grantCount = 0
        for (perm in permissions) {
            try {
                val granted = dpm.setPermissionGrantState(
                    admin, context.packageName, perm,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                if (granted) grantCount++
            } catch (e: Exception) {
                Log.w(TAG, "Could not grant $perm: ${e.message}")
            }
        }
        Log.d(TAG, "Granted $grantCount/${permissions.size} permissions to self")

        // Step 3: Mark permissions as granted so MainActivity skips PermissionSetupActivity
        prefs.permissionsGranted = true

        // Step 4: Log transfer metadata passed from Provisioner (optional)
        val transferredFrom = bundle?.getString("transferred_from")
        val transferTime    = bundle?.getLong("transfer_timestamp")
        Log.d(TAG, "Transferred from: $transferredFrom at $transferTime")

        // Step 5: Launch MainActivity → it will route to EnrollmentActivity
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("from_ownership_transfer", true)
        }
        context.startActivity(launchIntent)
    }
}