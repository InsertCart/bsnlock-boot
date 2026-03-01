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
    private lateinit var tvInstructions: TextView
    private lateinit var btnGrantAll: Button
    private lateinit var btnDone: Button

    // ─── FIX BUG #5: Sequential permission queue ────────────────────────────
    // Instead of firing all settingsLauncher.launch() calls simultaneously
    // (only the last one opens), we queue them and open each screen one at a time.
    private val pendingSettingsQueue = ArrayDeque<Intent>()

    private val runtimeLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateStatus()
        launchNextInQueue() // open next settings screen if pending
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateStatus()
        launchNextInQueue() // ← after each screen returns, open the next one
    }

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateStatus()
        launchNextInQueue()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_setup)

        prefs           = PreferencesManager(this)
        tvStatus        = findViewById(R.id.tvPermissionStatus)
        tvInstructions  = findViewById(R.id.tvTransferInstructions)
        btnGrantAll     = findViewById(R.id.btnGrantPermissions)
        btnDone         = findViewById(R.id.btnContinue)

        btnGrantAll.setOnClickListener { requestAllPermissions() }

        // ─── FIX BUG #1: Don't navigate to EnrollmentActivity ────────────────
        // The dealer must do TestDPC → Transfer Ownership FIRST.
        // After transfer, onTransferOwnershipComplete() fires and MainActivity
        // routes to EnrollmentActivity automatically. This button just closes the app.
        btnDone.text = "Done — Go to TestDPC"
        btnDone.setOnClickListener {
            if (!areEssentialGranted()) {
                Toast.makeText(this,
                    "Please grant all required permissions first.",
                    Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            // All permissions ready including Device Admin.
            // Instruct dealer to now go to TestDPC and transfer ownership.
            Toast.makeText(this,
                "✅ Setup complete!\n\n1. Open TestDPC app\n2. Go to Transfer Ownership\n3. Select EmiLock",
                Toast.LENGTH_LONG).show()
            // Close EmiLock — dealer goes to TestDPC now.
            // After transfer, EmiLock will auto-launch into EnrollmentActivity.
            finish()
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    // ─── FIX BUG #5: Queue-based sequential settings launch ─────────────────
    private fun requestAllPermissions() {
        pendingSettingsQueue.clear()

        // Step 1: Runtime permissions (dialog — no queue needed)
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
        // After runtime dialog closes, launchNextInQueue() opens the settings screens one by one

        // Queue settings screens in order (only the ones not yet granted)
        if (!isBatteryOptExempt()) {
            pendingSettingsQueue.add(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasOverlay()) {
            pendingSettingsQueue.add(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }

        if (!isAccessibilityEnabled()) {
            pendingSettingsQueue.add(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Enable 'EmiLock' in Accessibility settings",
                Toast.LENGTH_LONG).show()
        }

        // Device Admin — uses separate launcher (shows native Android dialog)
        // ─── FIX BUG #2: Device Admin is required ────────────────────────────
        // TestDPC's transferOwnership() requires EmiLock's admin to be ACTIVE.
        // This is not optional — without it, transfer crashes with IllegalArgumentException.
        if (!isDeviceAdmin()) {
            deviceAdminLauncher.launch(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        ComponentName(this@PermissionSetupActivity, MyDeviceAdminReceiver::class.java))
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "EmiLock requires Device Admin to allow ownership transfer from TestDPC. " +
                                "This is required for device protection.")
                }
            )
        }
    }

    private fun launchNextInQueue() {
        if (pendingSettingsQueue.isEmpty()) return
        val next = pendingSettingsQueue.removeFirst()
        settingsLauncher.launch(next)
    }

    private fun updateStatus() {
        val isAdmin  = isDeviceAdmin()
        val allReady = areEssentialGranted()

        val sb = StringBuilder()
        sb.appendLine("Permission Status:\n")
        sb.appendLine("📍 Location:        ${if (hasLocation()) "✅" else "❌"}")
        sb.appendLine("🔋 Battery Exempt:  ${if (isBatteryOptExempt()) "✅" else "❌"}")
        sb.appendLine("🪟 Overlay:         ${if (hasOverlay()) "✅" else "❌"}")
        sb.appendLine("🛡️ Device Admin:    ${if (isAdmin) "✅" else "❌ (REQUIRED for transfer)"}")
        sb.appendLine("♿ Accessibility:   ${if (isAccessibilityEnabled()) "✅" else "❌"}")
        sb.appendLine("📞 Phone State:     ${if (hasPhoneState()) "✅" else "❌"}")
        tvStatus.text = sb.toString()

        btnDone.isEnabled = allReady

        // Show next-step instructions once all permissions are ready
        if (::tvInstructions.isInitialized) {
            tvInstructions.text = if (allReady) {
                "✅ All done! Next steps:\n\n" +
                        "1. Tap 'Done — Go to TestDPC'\n" +
                        "2. Open TestDPC app\n" +
                        "3. Tap 'Transfer Ownership'\n" +
                        "4. Select EmiLock from the list\n\n" +
                        "EmiLock will open automatically after transfer."
            } else {
                "Grant all permissions above, then tap the button."
            }
        }
    }

    // ─── FIX BUG #2: Device Admin is now REQUIRED ───────────────────────────
    private fun areEssentialGranted(): Boolean =
        hasLocation() &&
                isBatteryOptExempt() &&
                hasOverlay() &&
                isAccessibilityEnabled() &&
                isDeviceAdmin()  // ← ADDED: required for TestDPC transfer to work

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