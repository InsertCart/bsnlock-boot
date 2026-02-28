package com.emilock.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.emilock.app.DeviceLockManager
import com.emilock.app.R
import com.emilock.app.data.api.ApiClient
import com.emilock.app.data.local.PreferencesManager
import com.emilock.app.data.model.HeartbeatRequest
import com.emilock.app.ui.LockScreenActivity
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MonitoringService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var prefs: PreferencesManager
    private lateinit var lockManager: DeviceLockManager
    private lateinit var fusedLocation: FusedLocationProviderClient
    private var currentLocation: Location? = null

    companion object {
        private const val TAG              = "EmiLock.Monitoring"
        private const val CHANNEL_ID       = "emilock_monitoring"
        private const val NOTIF_ID         = 1001
        private const val CHECK_INTERVAL   = 5 * 60 * 1000L   // 5 minutes
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        prefs       = PreferencesManager(this)
        lockManager = DeviceLockManager(this)
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()
        startForegroundCompat()

        startLocationUpdates()
        startPeriodicChecks()
        log("MonitoringService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("onStartCommand — service alive")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        log("Task removed — scheduling restart via AlarmManager")
        val restartIntent = Intent(applicationContext, MonitoringService::class.java)
        val pending = PendingIntent.getService(
            this, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 2000L,
            pending
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─── Foreground Notification ─────────────────────────────────────────────

    private fun startForegroundCompat() {
        val notification = buildNotification("EmiLock protection active")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            log("startForeground error: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Device Monitoring", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EmiLock Protected")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    // ─── Location ────────────────────────────────────────────────────────────

    private fun startLocationUpdates() {
        val req = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            TimeUnit.MINUTES.toMillis(3)
        ).build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                currentLocation = result.lastLocation
            }
        }

        try {
            fusedLocation.requestLocationUpdates(req, cb, Looper.getMainLooper())
        } catch (e: SecurityException) {
            log("Location permission denied: ${e.message}")
        }
    }

    // ─── Periodic polling ────────────────────────────────────────────────────

    private fun startPeriodicChecks() {
        serviceScope.launch {
            while (isActive) {
                try {
                    checkDeviceStatus()
                    sendHeartbeat()
                } catch (e: Exception) {
                    log("Periodic check error: ${e.message}")
                }
                delay(CHECK_INTERVAL)
            }
        }
    }

    // ─── Status check ────────────────────────────────────────────────────────

    private suspend fun checkDeviceStatus() {
        if (!prefs.isEnrolled) return
        val token = prefs.deviceToken?.trim() ?: return

        try {
            val response = ApiClient.api.checkDeviceStatus("Bearer $token")
            if (!response.isSuccessful) {
                updateNotification("API error ${response.code()}")
                return
            }

            val status = response.body() ?: return
            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            updateNotification("Last: $ts | Locked: ${status.isLocked}")
            log("Status: locked=${status.isLocked}, cmd=${status.pendingCommand}")

            // Handle uninstall command from dashboard
            if (status.lockStatus == "uninstalled") {
                deactivateAndStop()
                return
            }

            val cmd = status.pendingCommand
            when {
                cmd == "lock"   -> executeLock(status.lockMessage)
                cmd == "unlock" -> executeUnlock()
                cmd == null && status.isLocked  && !prefs.isLocked -> executeLock(status.lockMessage)
                cmd == null && !status.isLocked && prefs.isLocked  -> executeUnlock()
            }
        } catch (e: Exception) {
            log("checkDeviceStatus exception: ${e.message}")
        }
    }

    // ─── Heartbeat ───────────────────────────────────────────────────────────

    private suspend fun sendHeartbeat() {
        if (!prefs.isEnrolled) return
        val token = prefs.deviceToken?.trim() ?: return
        val loc   = currentLocation

        try {
            val req = HeartbeatRequest(
                latitude  = loc?.latitude  ?: 0.0,
                longitude = loc?.longitude ?: 0.0,
                timestamp = System.currentTimeMillis()
            )
            val response = ApiClient.api.sendHeartbeat("Bearer $token", req)
            if (response.isSuccessful) {
                response.body()?.pendingCommand?.let { cmd ->
                    when (cmd) {
                        "lock"   -> executeLock("Device is locked")
                        "unlock" -> executeUnlock()
                    }
                }
            }
        } catch (e: Exception) {
            log("sendHeartbeat exception: ${e.message}")
        }
    }

    // ─── Lock / Unlock execution ─────────────────────────────────────────────

    private fun executeLock(message: String?) {
        if (prefs.isLocked) return
        log("Executing LOCK")
        prefs.isLocked    = true
        prefs.lockMessage = message ?: "Device is locked. Please clear your EMI dues."

        // DPM hardening
        lockManager.applyLockCommand()

        // Show overlay lock screen
        showLockScreenActivity()
    }

    private fun executeUnlock() {
        if (!prefs.isLocked) return
        log("Executing UNLOCK")
        prefs.isLocked    = false
        prefs.lockMessage = null

        lockManager.applyUnlockCommand()
        sendUnlockBroadcast()
    }

    private fun showLockScreenActivity() {
        val intent = Intent(this, LockScreenActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        try { startActivity(intent) } catch (e: Exception) { log("startActivity failed: ${e.message}") }

        // Fallback: high-priority full-screen notification
        val nm     = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chanId = "emilock_lock_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(chanId, "Device Lock", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(this, 0, intent, pendingFlags)

        val notif = NotificationCompat.Builder(this, chanId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Device Locked")
            .setContentText("This device has been locked. Please contact your dealer.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setOngoing(true)
            .build()
        nm.notify(1002, notif)
    }

    private fun sendUnlockBroadcast() {
        val intent = Intent(LockScreenActivity.ACTION_UNLOCK).apply { setPackage(packageName) }
        sendBroadcast(intent)
    }

    // ─── Deactivate ──────────────────────────────────────────────────────────

    private fun deactivateAndStop() {
        log("Deactivating device — removing Device Owner restrictions")
        prefs.isLocked   = false
        prefs.isEnrolled = false
        lockManager.removeDeviceOwnerRestrictions()
        sendUnlockBroadcast()
        stopSelf()
    }

    private fun log(msg: String) = Log.d(TAG, msg)
}