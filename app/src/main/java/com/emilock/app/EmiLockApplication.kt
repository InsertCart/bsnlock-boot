package com.emilock.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.emilock.app.workers.ServiceRestartWorker
import java.util.concurrent.TimeUnit

class EmiLockApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        scheduleServiceMonitoring()
    }

    /**
     * Schedule a WorkManager job that runs every 15 minutes to:
     *  - Ensure MonitoringService is running
     *  - Re-show lock screen if device is in locked state
     */
    private fun scheduleServiceMonitoring() {
        val workRequest = PeriodicWorkRequestBuilder<ServiceRestartWorker>(
            15, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "emilock_service_monitor",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}