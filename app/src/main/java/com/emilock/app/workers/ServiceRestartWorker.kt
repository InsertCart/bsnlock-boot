package com.emilock.app.workers

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.emilock.app.data.local.PreferencesManager
import com.emilock.app.service.MonitoringService
import com.emilock.app.ui.LockScreenActivity

class ServiceRestartWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("EmiLock.Worker", "ServiceRestartWorker running")
        val prefs = PreferencesManager(applicationContext)

        // Re-show lock screen if still locked
        if (prefs.isLocked) {
            val lockIntent = Intent(applicationContext, LockScreenActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            applicationContext.startActivity(lockIntent)
        }

        // Restart monitoring service if enrolled
        if (prefs.isEnrolled) {
            val serviceIntent = Intent(applicationContext, MonitoringService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }
        }

        return Result.success()
    }
}