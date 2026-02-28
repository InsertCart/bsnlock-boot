package com.emilock.app.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.emilock.app.R
import com.emilock.app.data.local.PreferencesManager
import com.emilock.app.service.EmiLockAccessibilityService

class LockScreenActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        const val ACTION_UNLOCK = "com.emilock.app.ACTION_UNLOCK"
        private const val TAG   = "EmiLock.LockScreen"
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UNLOCK) {
                Log.d(TAG, "Unlock broadcast received")
                finishAndRemoveTask()
            }
        }
    }

    // Periodically bring this activity back to front while locked
    private val keepFrontRunnable = object : Runnable {
        override fun run() {
            if (prefs.isLocked) {
                hideSystemUI()
                try {
                    val tasks = (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).appTasks
                    if (tasks.isNotEmpty()) tasks[0].moveToFront()
                } catch (_: Exception) {}
            }
            handler.postDelayed(this, 2000)
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupWindowFlags()
        prefs = PreferencesManager(this)

        val filter = IntentFilter(ACTION_UNLOCK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        }

        if (!prefs.isLocked) { finish(); return }

        setContentView(R.layout.activity_lock_screen)
        setupUI()
        hideSystemUI()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* blocked */ }
        })

        handler.post(keepFrontRunnable)
        startPulseAnimation()
    }

    override fun onResume() {
        super.onResume()
        if (!prefs.isLocked) finishAndRemoveTask()
        else { hideSystemUI(); setupWindowFlags() }
    }

    override fun onDestroy() {
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        handler.removeCallbacks(keepFrontRunnable)
        super.onDestroy()
    }

    // ─── Window flags ────────────────────────────────────────────────────────

    private fun setupWindowFlags() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun hideSystemUI() {
        val ctrl = WindowInsetsControllerCompat(window, window.decorView)
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ─── UI setup ────────────────────────────────────────────────────────────

    private fun setupUI() {
        val tvDealer   = findViewById<TextView>(R.id.tvDealerName)
        val tvRetailer = findViewById<TextView>(R.id.tvRetailerName)
        val tvPhone    = findViewById<TextView>(R.id.tvRetailerPhone)
        val tvImei     = findViewById<TextView>(R.id.tvImei)
        val tvMsg      = findViewById<TextView>(R.id.tvLockMessage)
        val etCode     = findViewById<EditText>(R.id.etUnlockCode)
        val btnUnlock  = findViewById<Button>(R.id.btnUnlock)

        tvDealer.text   = prefs.dealerName   ?: "EmiLock"
        tvRetailer.text = prefs.retailerName ?: "Contact Dealer"
        tvPhone.text    = prefs.retailerPhone ?: ""
        tvImei.text     = prefs.imei1        ?: ""
        tvMsg.text      = prefs.lockMessage  ?: "Your EMI is overdue. Please contact your dealer to unlock."

        btnUnlock.setOnClickListener {
            val code = etCode.text.toString().trim()
            if (code.isEmpty()) {
                Toast.makeText(this, "Enter unlock code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            validateAndUnlock(code)
        }
    }

    private fun validateAndUnlock(code: String) {
        val clean = code.replace(Regex("[^0-9]"), "")
        val stored = prefs.unlockCode?.replace(Regex("[^0-9]"), "")

        val valid = when {
            stored != null && clean == stored -> { prefs.unlockCode = null; true }
            // Extend with HMAC time-based code if secret is stored
            prefs.unlockSecret != null -> validateTimeBasedCode(clean)
            else -> false
        }

        if (valid) {
            prefs.isLocked    = false
            prefs.lockMessage = null
            Toast.makeText(this, "✅ Device unlocked!", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
        } else {
            Toast.makeText(this, "❌ Invalid unlock code", Toast.LENGTH_SHORT).show()
        }
    }

    private fun validateTimeBasedCode(code: String): Boolean {
        val secret = prefs.unlockSecret ?: return false
        return try {
            val key = javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256")
            val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(key) }
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            val hash  = mac.doFinal(today.toByteArray())
            val hex   = hash.joinToString("") { "%02x".format(it) }
            val generated = (hex.substring(0, 6).toLong(16) % 1_000_000)
                .toString().padStart(6, '0')
            code == generated
        } catch (_: Exception) { false }
    }

    // ─── Key blocking ────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (prefs.isLocked) {
            startActivity(Intent(this, LockScreenActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            })
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        } else if (prefs.isLocked) {
            try {
                val intent = Intent(EmiLockAccessibilityService.ACTION_DISMISS_SYSTEM_UI)
                sendBroadcast(intent)
            } catch (_: Exception) {}
        }
    }

    // ─── Pulse animation ─────────────────────────────────────────────────────

    private fun startPulseAnimation() {
        val icon = try { findViewById<android.view.View>(R.id.ivLockIcon) } catch (_: Exception) { return }
        ObjectAnimator.ofPropertyValuesHolder(
            icon,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.15f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.15f),
            PropertyValuesHolder.ofFloat("alpha",  1f, 0.8f)
        ).apply {
            duration     = 1500
            repeatCount  = ObjectAnimator.INFINITE
            repeatMode   = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }.start()
    }
}