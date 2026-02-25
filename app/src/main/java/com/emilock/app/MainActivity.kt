package com.emilock.app

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var lockManager: DeviceLockManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        if (!dpm.isDeviceOwnerApp(packageName)) {
            finish()
            return
        }

        lockManager = DeviceLockManager(this)

        checkEmiStatus()
    }

    private fun checkEmiStatus() {
        Log.d("EMI", "Calling EMI status API...")

        val client = OkHttpClient()

        val body = """
            { "device_id": "TEST123" }
        """.trimIndent().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://xsx.insertcart.com/emi/status")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                Log.e("EMI", "API failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful || response.body == null) return

                val responseBody = response.body!!.string()
                Log.d("EMI", "API response: $responseBody")

                val json = JSONObject(responseBody)

                val emiStatus = json.optString("emi_status")
                val lockType = json.optString("lock_type")

                val blockedApps = mutableListOf<String>()
                val appsArray = json.optJSONArray("blocked_apps")
                if (appsArray != null) {
                    for (i in 0 until appsArray.length()) {
                        blockedApps.add(appsArray.getString(i))
                    }
                }

                runOnUiThread {
                    applyLockPolicy(emiStatus, lockType, blockedApps)
                }
            }
        })
    }

    private fun applyLockPolicy(
        emiStatus: String,
        lockType: String,
        blockedApps: List<String>
    ) {
        Log.d("EMI", "Applying policy: $emiStatus / $lockType / $blockedApps")

        when {
            emiStatus == "PAID" -> {
                lockManager.unlockAll()
            }

            lockType == "FULL" -> {
                lockManager.fullLock()
            }

            lockType == "PARTIAL" -> {
                lockManager.lockPartial(blockedApps)
            }
            else -> {
                lockManager.unlockAll()
            }
        }
    }
}
