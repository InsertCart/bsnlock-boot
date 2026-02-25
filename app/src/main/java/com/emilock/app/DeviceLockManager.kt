package com.emilock.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager

class DeviceLockManager(private val context: Context) {

    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin =
        ComponentName(context, MyDeviceAdminReceiver::class.java)
    private val prefs = context.getSharedPreferences("lock_prefs", Context.MODE_PRIVATE)

    private val HIDDEN_APPS_KEY = "hidden_apps"

    fun lockPartial(apps: List<String>) {
        // Hide the apps
        for (pkg in apps) {
            dpm.setApplicationHidden(admin, pkg, true)
        }
        // Save the list of hidden apps for future unlocking
        prefs.edit().putStringSet(HIDDEN_APPS_KEY, apps.toSet()).apply()

        // Add other restrictions
        dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
        dpm.setUninstallBlocked(admin, context.packageName, true)
    }

    fun unlockAll() {
        // To handle migration from the old version, we create a combined list of apps to unhide.
        val appsToUnHide = mutableSetOf<String>()

        // 1. Add apps from SharedPreferences (the new way)
        val newHiddenApps = prefs.getStringSet(HIDDEN_APPS_KEY, emptySet()) ?: emptySet()
        appsToUnHide.addAll(newHiddenApps)

        // 2. Add apps that might have been hidden by older versions of the code (the old, hardcoded way)
        appsToUnHide.add("com.whatsapp")
        appsToUnHide.add("com.instagram.android")
        appsToUnHide.add("com.facebook.katana")
        appsToUnHide.add("com.android.vending")

        // Unhide all apps in the combined list
        for (pkg in appsToUnHide) {
            dpm.setApplicationHidden(admin, pkg, false)
        }
        
        // Clear the saved list from SharedPreferences
        prefs.edit().remove(HIDDEN_APPS_KEY).apply()

        // Remove all other restrictions
        dpm.setLockTaskPackages(admin, arrayOf()) // Exit full lock (kiosk mode)
        dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
        dpm.setUninstallBlocked(admin, context.packageName, false)
    }

    fun fullLock() {
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
        dpm.setUninstallBlocked(admin, context.packageName, true)
    }
}
