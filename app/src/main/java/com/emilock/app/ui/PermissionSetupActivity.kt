package com.emilock.app.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.emilock.app.MyDeviceAdminReceiver
import com.emilock.app.R
import com.emilock.app.data.local.PreferencesManager

class PermissionSetupActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var tvStatus: TextView
    private lateinit var btnGrantAll: Button
    private lateinit var btnContinue: Button

    private val runtimeLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateStatus() }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateStatus() }

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_setup)

        prefs    = PreferencesManager(this)
        tvStatus = findViewById(R.id.tvPermissionStatus)
        btnGrantAll = findViewById(R.id.btnGrantPermissions)
        btnContinue = findViewById(R.id.btnContinue)

        btnGrantAll.setOnClickListener { requestAllPermissions() }
        btnContinue.setOnClickListener {
            if (areEssentialGranted()) {
                prefs.permissionsGranted = true
                startActivity(Intent(this, EnrollmentActivity::class.java))
                finish()
            } else {
                Toast.makeText(this, "Please grant all required permissions first.", Toast.LENGTH_LONG).show()
            }
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun requestAllPermissions() {
        // Runtime permissions
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        runtimeLauncher.launch(perms.toTypedArray())

        // Battery optimization
        if (!isBatteryOptExempt()) {
            settingsLauncher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }

        // Overlay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            settingsLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }

        // Device Admin
        if (!isDeviceAdmin()) {
            deviceAdminLauncher.launch(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        ComponentName(this@PermissionSetupActivity, MyDeviceAdminReceiver::class.java))
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "EmiLock requires Device Admin to manage device protection.")
                }
            )
        }

        // Accessibility
        if (!isAccessibilityEnabled()) {
            settingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Enable 'EmiLock' in Accessibility settings", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateStatus() {
        val sb = StringBuilder()
        sb.appendLine("Permission Status:\n")
        sb.appendLine("📍 Location:        ${if (hasLocation()) "✅" else "❌"}")
        sb.appendLine("🔋 Battery Exempt:  ${if (isBatteryOptExempt()) "✅" else "❌"}")
        sb.appendLine("🪟 Overlay:         ${if (hasOverlay()) "✅" else "❌"}")
        sb.appendLine("🛡️ Device Admin:    ${if (isDeviceAdmin()) "✅" else "❌ (or Device Owner)"}")
        sb.appendLine("♿ Accessibility:   ${if (isAccessibilityEnabled()) "✅" else "❌"}")
        sb.appendLine("📞 Phone State:     ${if (hasPhoneState()) "✅" else "❌"}")
        tvStatus.text = sb.toString()

        btnContinue.isEnabled = areEssentialGranted()
    }

    private fun areEssentialGranted(): Boolean =
        hasLocation() && isBatteryOptExempt() && hasOverlay() && isAccessibilityEnabled()

    private fun hasLocation() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasPhoneState() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun isBatteryOptExempt(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun hasOverlay() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        Settings.canDrawOverlays(this) else true

    private fun isDeviceAdmin(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, MyDeviceAdminReceiver::class.java)) ||
                dpm.isDeviceOwnerApp(packageName)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabled?.contains(packageName) == true
    }
}