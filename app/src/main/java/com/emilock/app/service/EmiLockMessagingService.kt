package com.emilock.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.emilock.app.DeviceLockManager
import com.emilock.app.R
import com.emilock.app.data.api.ApiClient
import com.emilock.app.data.local.PreferencesManager
import com.emilock.app.data.model.HeartbeatRequest
import com.emilock.app.data.model.TokenUpdateRequest
import com.emilock.app.ui.LockScreenActivity
import kotlinx.coroutines.*

class EmiLockMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "EmiLock.FCM"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data   = message.data
        val action = data["action"]
        Log.d(TAG, "FCM received: action=$action")

        val prefs       = PreferencesManager(this)
        val lockManager = DeviceLockManager(this)

        when (action) {

            "lock" -> {
                val msg = data["message"] ?: "Device Locked"
                if (!prefs.isLocked) {
                    prefs.isLocked    = true
                    prefs.lockMessage = msg
                    lockManager.applyLockCommand()
                    showLockScreen()
                }
            }

            "unlock" -> {
                if (prefs.isLocked) {
                    prefs.isLocked    = false
                    prefs.lockMessage = null
                    lockManager.applyUnlockCommand()
                    sendBroadcast(
                        Intent(LockScreenActivity.ACTION_UNLOCK).apply { setPackage(packageName) }
                    )
                }
            }

            "location" -> {
                sendLocationHeartbeat(prefs)
            }

            "uninstall" -> {
                prefs.isLocked   = false
                prefs.isEnrolled = false
                lockManager.removeDeviceOwnerRestrictions()
                sendBroadcast(
                    Intent(LockScreenActivity.ACTION_UNLOCK).apply { setPackage(packageName) }
                )
            }

            else -> {
                // Generic notification
                val title = data["title"]
                val body  = data["body"]
                if (!title.isNullOrEmpty() && !body.isNullOrEmpty()) {
                    showGenericNotification(title, body)
                }
            }
        }
    }

    // ─── Location heartbeat triggered via FCM ────────────────────────────────

    private fun sendLocationHeartbeat(prefs: PreferencesManager) {
        val token = prefs.deviceToken ?: return
        val fused = LocationServices.getFusedLocationProviderClient(this)
        val cts   = CancellationTokenSource()

        try {
            fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .addOnSuccessListener { loc ->
                    fireHeartbeat(token, loc?.latitude ?: 0.0, loc?.longitude ?: 0.0)
                }
                .addOnFailureListener {
                    fireHeartbeat(token, 0.0, 0.0)
                }
        } catch (_: SecurityException) {
            fireHeartbeat(token, 0.0, 0.0)
        }
    }

    private fun fireHeartbeat(token: String, lat: Double, lng: Double) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = ApiClient.api.sendHeartbeat(
                    "Bearer $token",
                    HeartbeatRequest(lat, lng, System.currentTimeMillis())
                )
                if (response.isSuccessful) {
                    response.body()?.pendingCommand?.let { cmd ->
                        val prefs = PreferencesManager(this@EmiLockMessagingService)
                        val lm    = DeviceLockManager(this@EmiLockMessagingService)
                        when (cmd) {
                            "lock" -> if (!prefs.isLocked) {
                                prefs.isLocked    = true
                                prefs.lockMessage = "Device Locked"
                                lm.applyLockCommand()
                                showLockScreen()
                            }
                            "unlock" -> if (prefs.isLocked) {
                                prefs.isLocked    = false
                                prefs.lockMessage = null
                                lm.applyUnlockCommand()
                                sendBroadcast(
                                    Intent(LockScreenActivity.ACTION_UNLOCK).apply { setPackage(packageName) }
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "FCM heartbeat error: ${e.message}")
            }
        }
    }

    // ─── Show lock screen overlay ────────────────────────────────────────────

    private fun showLockScreen() {
        val intent = Intent(this, LockScreenActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        try { startActivity(intent) } catch (_: Exception) {}

        val nm     = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chanId = "emilock_lock_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(chanId, "Device Lock", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi    = PendingIntent.getActivity(this, 0, intent, flags)

        nm.notify(1002,
            NotificationCompat.Builder(this, chanId)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Device Locked")
                .setContentText("Your device has been locked.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)
                .setOngoing(true)
                .build()
        )
    }

    private fun showGenericNotification(title: String, body: String) {
        val nm     = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chanId = "emilock_notif"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(chanId, "Notifications", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        nm.notify(System.currentTimeMillis().toInt(),
            NotificationCompat.Builder(this, chanId)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
        )
    }

    // ─── Token refresh ───────────────────────────────────────────────────────

    override fun onNewToken(token: String) {
        Log.d(TAG, "New FCM token: $token")
        val prefs = PreferencesManager(this)
        prefs.fcmToken = token

        val imei1       = prefs.imei1 ?: return
        val deviceToken = prefs.deviceToken ?: return

        CoroutineScope(Dispatchers.IO).launch {
            repeat(3) { attempt ->
                try {
                    val res = ApiClient.api.updateToken(
                        imei1   = imei1,
                        token   = "Bearer $deviceToken",
                        request = TokenUpdateRequest(token)
                    )
                    if (res.isSuccessful) {
                        Log.d(TAG, "FCM token updated on server (attempt ${attempt + 1})")
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Token update failed (${attempt + 1}): ${e.message}")
                }
                if (attempt < 2) delay(5000)
            }
        }
    }
}