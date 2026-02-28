package com.emilock.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.emilock.app.data.local.PreferencesManager
import com.emilock.app.service.MonitoringService
import com.emilock.app.ui.LockScreenActivity

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        Log.d("EmiLock.Boot", "Boot/update broadcast received: $action")

        val prefs = PreferencesManager(context)
        Log.d("EmiLock.Boot", "enrolled=${prefs.isEnrolled}, locked=${prefs.isLocked}")

        // If device was locked before reboot, show lock screen IMMEDIATELY
        if (prefs.isLocked) {
            Log.d("EmiLock.Boot", "Device is LOCKED — showing lock screen")
            val lockIntent = Intent(context, LockScreenActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.startActivity(lockIntent)
        }

        // Start monitoring service if device is enrolled
        if (prefs.isEnrolled) {
            Log.d("EmiLock.Boot", "Starting MonitoringService")
            val serviceIntent = Intent(context, MonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}