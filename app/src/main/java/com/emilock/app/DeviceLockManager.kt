package com.emilock.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.util.Log

/**
 * DeviceLockManager — EmiLock's lock enforcement engine.
 *
 * EmiLock runs as **Device Owner**, giving it the strongest possible
 * control over the device. This manager handles both:
 *   - "full" lock  → kiosk / lock-task mode (device pinned to our app)
 *   - "partial"    → hide a list of apps
 *   - API commands → "lock" and "unlock" from the backend
 *
 * Lock screen overlay (LockScreenActivity) is handled separately and
 * works in parallel with the DPM restrictions below.
 */
class DeviceLockManager(private val context: Context) {

    private val tag = "DeviceLockManager"

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)
    private val hiddenAppsPrefs = context.getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)
    private val HIDDEN_APPS_KEY = "hidden_apps"

    // ─── Device Owner check ──────────────────────────────────────────────────

    val isDeviceOwner: Boolean
        get() = dpm.isDeviceOwnerApp(context.packageName)

    val isDeviceAdmin: Boolean
        get() = dpm.isAdminActive(admin)

    // ─── API Command handlers ────────────────────────────────────────────────

    /**
     * Called when backend sends "lock" command.
     * Applies full kiosk lock + Device Owner restrictions.
     */
    fun applyLockCommand() {
        Log.d(tag, "Applying LOCK command from API")
        applyDeviceOwnerRestrictions()
        fullLock()
    }

    /**
     * Called when backend sends "unlock" command.
     * Lifts all restrictions.
     */
    fun applyUnlockCommand() {
        Log.d(tag, "Applying UNLOCK command from API")
        unlockAll()
    }

    // ─── Lock modes ──────────────────────────────────────────────────────────

    /** Kiosk / lock-task mode: device is pinned to EmiLock only */
    fun fullLock() {
        if (!isDeviceOwner) {
            Log.w(tag, "Not Device Owner — fullLock skipped")
            return
        }
        try {
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
            dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
            dpm.setUninstallBlocked(admin, context.packageName, true)
            Log.d(tag, "fullLock applied")
        } catch (e: Exception) {
            Log.e(tag, "fullLock error: ${e.message}")
        }
    }

    /** Hide a specific list of apps (partial lock) */
    fun lockPartial(apps: List<String>) {
        if (!isDeviceOwner) return
        for (pkg in apps) {
            try { dpm.setApplicationHidden(admin, pkg, true) } catch (_: Exception) {}
        }
        hiddenAppsPrefs.edit().putStringSet(HIDDEN_APPS_KEY, apps.toSet()).apply()
        dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
        dpm.setUninstallBlocked(admin, context.packageName, true)
    }

    /** Remove all DPM restrictions */
    fun unlockAll() {
        // Unhide apps
        val savedApps = hiddenAppsPrefs.getStringSet(HIDDEN_APPS_KEY, emptySet()) ?: emptySet()
        for (pkg in savedApps) {
            try { dpm.setApplicationHidden(admin, pkg, false) } catch (_: Exception) {}
        }
        hiddenAppsPrefs.edit().remove(HIDDEN_APPS_KEY).apply()

        if (!isDeviceOwner) return
        try {
            dpm.setLockTaskPackages(admin, arrayOf())
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
            dpm.setUninstallBlocked(admin, context.packageName, false)
            Log.d(tag, "unlockAll applied")
        } catch (e: Exception) {
            Log.e(tag, "unlockAll error: ${e.message}")
        }
    }

    // ─── Device Owner exclusive hardening ───────────────────────────────────

    /**
     * Apply strong restrictions available only to Device Owners.
     * Called once after enrollment is complete.
     */
    fun applyDeviceOwnerRestrictions() {
        if (!isDeviceOwner) return
        try {
            // Prevent factory reset
            dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
            // Prevent safe-boot (avoids bypassing our service)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            // Block USB file transfer (prevents APK sideloading)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
            // Prevent adding accounts (avoids Google account tricks)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS)
            // Prevent app uninstall
            dpm.setUninstallBlocked(admin, context.packageName, true)
            Log.d(tag, "Device Owner restrictions applied")
        } catch (e: Exception) {
            Log.e(tag, "applyDeviceOwnerRestrictions error: ${e.message}")
        }
    }

    /**
     * Remove Device Owner restrictions for deactivation / uninstall flow.
     */
    fun removeDeviceOwnerRestrictions() {
        if (!isDeviceOwner) return
        try {
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS)
            dpm.setUninstallBlocked(admin, context.packageName, false)
            dpm.removeActiveAdmin(admin)
        } catch (e: Exception) {
            Log.e(tag, "removeDeviceOwnerRestrictions error: ${e.message}")
        }
    }

    /** Immediately lock the screen using DPM */
    fun lockScreenNow() {
        try {
            if (isDeviceAdmin || isDeviceOwner) dpm.lockNow()
        } catch (e: Exception) {
            Log.e(tag, "lockNow error: ${e.message}")
        }
    }
}