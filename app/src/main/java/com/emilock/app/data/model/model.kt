package com.emilock.app.data.model

import com.google.gson.annotations.SerializedName

data class DeviceInfo(
    @SerializedName("manufacturer") val manufacturer: String,
    @SerializedName("model")        val model: String,
    @SerializedName("android_version") val androidVersion: String,
    @SerializedName("phone_number") val phoneNumber: String
)

data class EnrollmentRequest(
    @SerializedName("imei1")       val imei1: String,
    @SerializedName("imei2")       val imei2: String,
    @SerializedName("fcm_token")   val fcmToken: String,
    @SerializedName("device_info") val deviceInfo: DeviceInfo
)

data class EnrollmentResponse(
    @SerializedName("device_token")  val deviceToken: String,
    @SerializedName("retailer_id")   val retailerId: Any?,
    @SerializedName("retailer_name") val retailerName: String?,
    @SerializedName("retailer_phone") val retailerPhone: String?,
    @SerializedName("device_info")   val deviceInfo: DeviceInfo?
)

data class DeviceStatusResponse(
    @SerializedName("is_locked")       val isLocked: Boolean,
    @SerializedName("lock_status")     val lockStatus: String,   // "locked" | "unlocked" | "uninstalled"
    @SerializedName("lock_message")    val lockMessage: String? = null,
    @SerializedName("pending_command") val pendingCommand: String? = null
)

data class HeartbeatRequest(
    @SerializedName("latitude")  val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("timestamp") val timestamp: Long
)

data class HeartbeatResponse(
    @SerializedName("status")          val status: String,
    @SerializedName("pending_command") val pendingCommand: String? = null
)

data class TokenUpdateRequest(
    @SerializedName("fcm_token") val fcmToken: String
)

data class NotificationRequest(
    @SerializedName("message")   val message: String,
    @SerializedName("latitude")  val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("timestamp") val timestamp: Long
)