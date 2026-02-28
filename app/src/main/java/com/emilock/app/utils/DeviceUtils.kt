package com.emilock.app.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat

object DeviceUtils {

    fun manufacturer(): String = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    fun model(): String        = Build.MODEL
    fun androidVersion(): String = Build.VERSION.RELEASE

    @SuppressLint("MissingPermission", "HardwareIds")
    fun imei1(context: Context): String? {
        if (!hasPhonePermission(context)) return null
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tm.getImei(0) ?: tm.deviceId
            else @Suppress("DEPRECATION") tm.deviceId
        } catch (_: Exception) { null }
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun imei2(context: Context): String? {
        if (!hasPhonePermission(context)) return null
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tm.getImei(1) else null
        } catch (_: Exception) { null }
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun phoneNumber(context: Context): String? {
        if (!hasPhonePermission(context)) return null
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.line1Number?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    private fun hasPhonePermission(context: Context) =
        ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED
}