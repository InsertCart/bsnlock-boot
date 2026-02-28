package com.emilock.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.messaging.FirebaseMessaging
import com.emilock.app.DeviceLockManager
import com.emilock.app.R
import com.emilock.app.data.api.ApiClient
import com.emilock.app.data.local.PreferencesManager
import com.emilock.app.data.model.DeviceInfo
import com.emilock.app.data.model.EnrollmentRequest
import com.emilock.app.data.model.TokenUpdateRequest
import com.emilock.app.service.MonitoringService
import com.emilock.app.utils.DeviceUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class EnrollmentActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager

    // Views
    private lateinit var tvManufacturer: TextView
    private lateinit var tvModel: TextView
    private lateinit var tvAndroidVersion: TextView
    private lateinit var etImei1: EditText
    private lateinit var etImei2: EditText
    private lateinit var etPhoneNumber: EditText
    private lateinit var checkboxTerms: CheckBox
    private lateinit var btnEnroll: Button
    private lateinit var progressBar: ProgressBar

    companion object { private const val TAG = "EmiLock.Enroll" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = PreferencesManager(this)

        // Already enrolled → jump straight to monitoring
        if (prefs.isEnrolled) {
            startMonitoringAndFinish()
            return
        }

        setContentView(R.layout.activity_enrollment)
        bindViews()
        loadDeviceInfo()
        fetchFcmToken()
    }

    private fun bindViews() {
        tvManufacturer    = findViewById(R.id.tvManufacturer)
        tvModel           = findViewById(R.id.tvModel)
        tvAndroidVersion  = findViewById(R.id.tvAndroidVersion)
        etImei1           = findViewById(R.id.etImei1)
        etImei2           = findViewById(R.id.etImei2)
        etPhoneNumber     = findViewById(R.id.etPhoneNumber)
        checkboxTerms     = findViewById(R.id.checkboxTerms)
        btnEnroll         = findViewById(R.id.btnEnroll)
        progressBar       = findViewById(R.id.progressBar)

        checkboxTerms.setOnCheckedChangeListener { _, _ -> updateEnrollButton() }
        etImei1.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateEnrollButton() }
        })

        btnEnroll.setOnClickListener { enroll() }
        btnEnroll.isEnabled = false
    }

    private fun updateEnrollButton() {
        btnEnroll.isEnabled = checkboxTerms.isChecked && etImei1.text.isNotEmpty()
    }

    private fun loadDeviceInfo() {
        tvManufacturer.text   = DeviceUtils.manufacturer()
        tvModel.text          = DeviceUtils.model()
        tvAndroidVersion.text = DeviceUtils.androidVersion()

        etImei1.setText(DeviceUtils.imei1(this) ?: "")
        etImei2.setText(DeviceUtils.imei2(this) ?: "")
        etPhoneNumber.setText(DeviceUtils.phoneNumber(this) ?: "")
    }

    private fun fetchFcmToken() {
        lifecycleScope.launch {
            repeat(5) { attempt ->
                try {
                    val token = FirebaseMessaging.getInstance().token.await()
                    if (!token.isNullOrEmpty()) {
                        prefs.fcmToken = token
                        Log.d(TAG, "FCM token ready")
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "FCM token attempt ${attempt + 1} failed: ${e.message}")
                    delay(2000)
                }
            }
        }
    }

    private fun enroll() {
        val fcmToken = prefs.fcmToken
        if (fcmToken.isNullOrEmpty()) {
            fetchFcmToken()
            Toast.makeText(this, "Generating security token, please wait…", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        btnEnroll.isEnabled    = false

        val imei1  = etImei1.text.toString().trim()
        val imei2  = etImei2.text.toString().trim()
        val phone  = etPhoneNumber.text.toString().trim()

        val request = EnrollmentRequest(
            imei1      = imei1,
            imei2      = imei2,
            fcmToken   = fcmToken,
            deviceInfo = DeviceInfo(
                manufacturer   = tvManufacturer.text.toString(),
                model          = tvModel.text.toString(),
                androidVersion = tvAndroidVersion.text.toString(),
                phoneNumber    = phone
            )
        )

        lifecycleScope.launch {
            try {
                val response = ApiClient.api.enrollDevice(request)
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        prefs.apply {
                            isEnrolled    = true
                            deviceId      = imei1
                            deviceToken   = body.deviceToken
                            this.imei1    = imei1
                            this.imei2    = imei2
                            phoneNumber   = phone
                            retailerName  = body.retailerName  ?: ""
                            retailerPhone = body.retailerPhone ?: ""
                            dealerName    = body.retailerId?.toString() ?: ""
                        }

                        // Sync FCM token
                        try {
                            ApiClient.api.updateToken(
                                imei1   = imei1,
                                token   = "Bearer ${body.deviceToken}",
                                request = TokenUpdateRequest(fcmToken)
                            )
                        } catch (_: Exception) {}

                        // Apply Device Owner restrictions after enrollment
                        DeviceLockManager(this@EnrollmentActivity).applyDeviceOwnerRestrictions()

                        Toast.makeText(this@EnrollmentActivity, "Enrolled successfully!", Toast.LENGTH_SHORT).show()
                        startMonitoringAndFinish()
                    }
                } else {
                    Toast.makeText(this@EnrollmentActivity, "Enrollment failed: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@EnrollmentActivity, "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                progressBar.visibility = View.GONE
                btnEnroll.isEnabled    = true
            }
        }
    }

    private fun startMonitoringAndFinish() {
        val intent = Intent(this, MonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        finishAndRemoveTask()
    }
}