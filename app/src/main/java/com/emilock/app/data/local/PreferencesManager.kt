package com.emilock.app.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PreferencesManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "emilock_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_ENROLLED        = "is_enrolled"
        private const val KEY_DEVICE_ID       = "device_id"
        private const val KEY_DEVICE_TOKEN    = "device_token"
        private const val KEY_IMEI1           = "imei1"
        private const val KEY_IMEI2           = "imei2"
        private const val KEY_PHONE_NUMBER    = "phone_number"
        private const val KEY_RETAILER_NAME   = "retailer_name"
        private const val KEY_RETAILER_PHONE  = "retailer_phone"
        private const val KEY_DEALER_NAME     = "dealer_name"
        private const val KEY_IS_LOCKED       = "is_locked"
        private const val KEY_LOCK_MESSAGE    = "lock_message"
        private const val KEY_PERMISSIONS_OK  = "permissions_granted"
        private const val KEY_FCM_TOKEN       = "fcm_token"
        private const val KEY_UNLOCK_CODE     = "unlock_code"
        private const val KEY_UNLOCK_SECRET   = "unlock_secret"
    }

    var isEnrolled: Boolean
        get() = prefs.getBoolean(KEY_ENROLLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENROLLED, value).apply()

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var deviceToken: String?
        get() = prefs.getString(KEY_DEVICE_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_TOKEN, value).apply()

    var imei1: String?
        get() = prefs.getString(KEY_IMEI1, null)
        set(value) = prefs.edit().putString(KEY_IMEI1, value).apply()

    var imei2: String?
        get() = prefs.getString(KEY_IMEI2, null)
        set(value) = prefs.edit().putString(KEY_IMEI2, value).apply()

    var phoneNumber: String?
        get() = prefs.getString(KEY_PHONE_NUMBER, null)
        set(value) = prefs.edit().putString(KEY_PHONE_NUMBER, value).apply()

    var retailerName: String?
        get() = prefs.getString(KEY_RETAILER_NAME, null)
        set(value) = prefs.edit().putString(KEY_RETAILER_NAME, value).apply()

    var retailerPhone: String?
        get() = prefs.getString(KEY_RETAILER_PHONE, null)
        set(value) = prefs.edit().putString(KEY_RETAILER_PHONE, value).apply()

    var dealerName: String?
        get() = prefs.getString(KEY_DEALER_NAME, null)
        set(value) = prefs.edit().putString(KEY_DEALER_NAME, value).apply()

    var isLocked: Boolean
        get() = prefs.getBoolean(KEY_IS_LOCKED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOCKED, value).apply()

    var lockMessage: String?
        get() = prefs.getString(KEY_LOCK_MESSAGE, null)
        set(value) = prefs.edit().putString(KEY_LOCK_MESSAGE, value).apply()

    var permissionsGranted: Boolean
        get() = prefs.getBoolean(KEY_PERMISSIONS_OK, false)
        set(value) = prefs.edit().putBoolean(KEY_PERMISSIONS_OK, value).apply()

    var fcmToken: String?
        get() = prefs.getString(KEY_FCM_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_FCM_TOKEN, value).apply()

    var unlockCode: String?
        get() = prefs.getString(KEY_UNLOCK_CODE, null)
        set(value) = prefs.edit().putString(KEY_UNLOCK_CODE, value).apply()

    var unlockSecret: String?
        get() = prefs.getString(KEY_UNLOCK_SECRET, null)
        set(value) = prefs.edit().putString(KEY_UNLOCK_SECRET, value).apply()

    fun clear() = prefs.edit().clear().apply()
}