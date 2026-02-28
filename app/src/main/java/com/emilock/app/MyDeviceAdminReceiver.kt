package com.emilock.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.emilock.app.data.local.PreferencesManager

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d("EmiLock", "Device Admin ENABLED")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? {
        val prefs = PreferencesManager(context)
        return if (prefs.isEnrolled) {
            "WARNING: This device is protected by EmiLock. Disabling this administrator will violate your EMI agreement."
        } else null
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d("EmiLock", "Device Admin DISABLED")
    }

    /**
     * Called when the device is provisioned as Device Owner via ADB or NFC bump.
     * Immediately apply hardening restrictions.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.d("EmiLock", "Device Owner provisioning complete — applying hardening")
        DeviceLockManager(context).applyDeviceOwnerRestrictions()
    }
}