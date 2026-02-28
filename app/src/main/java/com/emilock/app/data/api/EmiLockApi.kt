package com.emilock.app.data.api

import com.emilock.app.data.model.DeviceStatusResponse
import com.emilock.app.data.model.EnrollmentRequest
import com.emilock.app.data.model.EnrollmentResponse
import com.emilock.app.data.model.HeartbeatRequest
import com.emilock.app.data.model.HeartbeatResponse
import com.emilock.app.data.model.NotificationRequest
import com.emilock.app.data.model.TokenUpdateRequest
import retrofit2.Response
import retrofit2.http.*

interface EmiLockApi {

    /** Enroll a new device and receive a JWT deviceToken */
    @POST("api/enroll")
    suspend fun enrollDevice(
        @Body request: EnrollmentRequest
    ): Response<EnrollmentResponse>

    /** Poll for pending lock/unlock commands */
    @GET("api/device/status")
    suspend fun checkDeviceStatus(
        @Header("Authorization") token: String
    ): Response<DeviceStatusResponse>

    /** Send a location heartbeat; server may return a pending command */
    @POST("api/device/heartbeat")
    suspend fun sendHeartbeat(
        @Header("Authorization") token: String,
        @Body request: HeartbeatRequest
    ): Response<HeartbeatResponse>

    /** Update FCM token on the server */
    @POST("api/device/update_token.php")
    suspend fun updateToken(
        @Query("imei1") imei1: String,
        @Header("Authorization") token: String,
        @Body request: TokenUpdateRequest
    ): Response<Void>

    /** Send a tamper/alert notification to the dashboard */
    @POST("api/device/notifications")
    suspend fun sendNotification(
        @Header("Authorization") token: String,
        @Body request: NotificationRequest
    ): Response<Void>
}