package com.mobilemouse

import android.content.*
import android.graphics.Color
import android.os.*
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mobilemouse.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var serverBinder: StylusServerService.LocalBinder? = null
    private var isBound = false
    private var isFullScreen = false
    private var showTrackpadButtons = true

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serverBinder = service as StylusServerService.LocalBinder
            isBound = true
            setupStylusView()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            serverBinder = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen on while app is active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Start and bind to the service
        val intent = Intent(this, StylusServerService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        val prefs = getSharedPreferences("mobile_mouse_prefs", Context.MODE_PRIVATE)

        // Palm rejection toggle button
        val initialPalm = prefs.getBoolean("palm_rejection", true)
        binding.stylusView.isPalmRejectionEnabled = initialPalm
        updatePalmButton(initialPalm)

        binding.btnPalmToggle.setOnClickListener {
            val enabled = !binding.stylusView.isPalmRejectionEnabled
            binding.stylusView.isPalmRejectionEnabled = enabled
            prefs.edit().putBoolean("palm_rejection", enabled).apply()
            updatePalmButton(enabled)
        }

        // Mode toggle button: Absolute (Tablet/Pen) vs Relative (Trackpad/Mouse)
        updateModeButton(binding.stylusView.isRelativeMode)
        binding.btnModeToggle.setOnClickListener {
            val isRel = !binding.stylusView.isRelativeMode
            binding.stylusView.isRelativeMode = isRel
            updateModeButton(isRel)
        }

        // Trackpad buttons preference
        showTrackpadButtons = prefs.getBoolean("trackpad_buttons", true)
        binding.stylusView.showTrackpadButtons = showTrackpadButtons
        updateFloatingButtonsLabel()

        // Fullscreen toggle buttons
        binding.btnFullscreenToggle.setOnClickListener {
            setFullScreen(true)
        }

        binding.btnFloatingExit.setOnClickListener {
            setFullScreen(false)
        }

        binding.btnFloatingMode.setOnClickListener {
            val isRel = !binding.stylusView.isRelativeMode
            binding.stylusView.isRelativeMode = isRel
            updateModeButton(isRel)
        }

        binding.btnFloatingButtons.setOnClickListener {
            showTrackpadButtons = !showTrackpadButtons
            binding.stylusView.showTrackpadButtons = showTrackpadButtons
            prefs.edit().putBoolean("trackpad_buttons", showTrackpadButtons).apply()
            updateFloatingButtonsLabel()
        }

        val initialFullScreen = prefs.getBoolean("fullscreen_mode", false)
        if (initialFullScreen) {
            setFullScreen(true)
        }

        // Poll connection status
        lifecycleScope.launch {
            while (true) {
                delay(1000)
                updateStatus()
            }
        }
    }

    private fun setupStylusView() {
        val server = serverBinder?.getServer() ?: return

        binding.stylusView.onStylusEvent = { packet ->
            server.sendPacket(packet)
        }

        binding.stylusView.onPressureChanged = { pressure ->
            runOnUiThread {
                val pct = (pressure * 100).toInt()
                binding.tvPressure.text = "$pct%"
            }
        }

        binding.stylusView.onEventRate = { hz ->
            runOnUiThread {
                binding.tvEventRate.text = "$hz Hz"
            }
        }
    }

    private fun updateStatus() {
        val server = serverBinder?.getServer()
        val count = server?.clientCount ?: 0

        runOnUiThread {
            binding.tvClients.text = count.toString()

            if (count > 0) {
                binding.statusDot.setBackgroundResource(R.drawable.dot_connected)
                binding.tvStatus.text = String.format(
                    Locale.US,
                    "Connected: %d PC client(s)", count
                )
                binding.tvStatus.setTextColor(Color.parseColor("#4CAF50"))
            } else {
                binding.statusDot.setBackgroundResource(R.drawable.dot_disconnected)
                binding.tvStatus.text = "Waiting for PC connection..."
                binding.tvStatus.setTextColor(Color.parseColor("#CCCCCC"))
            }
        }
    }

    private fun updatePalmButton(enabled: Boolean) {
        if (enabled) {
            binding.btnPalmToggle.text = getString(R.string.palm_rejection_on)
            binding.btnPalmToggle.setBackgroundColor(Color.parseColor("#22C55E"))
        } else {
            binding.btnPalmToggle.text = getString(R.string.palm_rejection_off)
            binding.btnPalmToggle.setBackgroundColor(Color.parseColor("#475569"))
        }
    }

    private fun updateModeButton(isRel: Boolean) {
        if (isRel) {
            binding.btnModeToggle.text = getString(R.string.mode_mouse)
            binding.btnModeToggle.setBackgroundColor(Color.parseColor("#0F3460"))
            binding.btnFloatingMode.text = "Trackpad"
            binding.btnFloatingMode.setBackgroundColor(Color.parseColor("#0F3460"))
            binding.btnFloatingButtons.visibility = View.VISIBLE
        } else {
            binding.btnModeToggle.text = getString(R.string.mode_pen)
            binding.btnModeToggle.setBackgroundColor(Color.parseColor("#E94560"))
            binding.btnFloatingMode.text = "Tablet"
            binding.btnFloatingMode.setBackgroundColor(Color.parseColor("#E94560"))
            binding.btnFloatingButtons.visibility = View.GONE
        }
    }

    private fun updateFloatingButtonsLabel() {
        if (showTrackpadButtons) {
            binding.btnFloatingButtons.text = getString(R.string.buttons_on)
            binding.btnFloatingButtons.setBackgroundColor(Color.parseColor("#1B2A47"))
        } else {
            binding.btnFloatingButtons.text = getString(R.string.buttons_off)
            binding.btnFloatingButtons.setBackgroundColor(Color.parseColor("#374151"))
        }
    }

    private fun setFullScreen(enabled: Boolean) {
        isFullScreen = enabled
        val prefs = getSharedPreferences("mobile_mouse_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("fullscreen_mode", enabled).apply()

        binding.stylusView.isFullScreen = enabled

        val lp = binding.stylusView.layoutParams as ViewGroup.MarginLayoutParams
        if (enabled) {
            binding.cardStatus.visibility = View.GONE
            binding.cardStats.visibility = View.GONE
            binding.layoutFloatingOverlay.visibility = View.VISIBLE
            lp.setMargins(0, 0, 0, 0)
        } else {
            binding.cardStatus.visibility = View.VISIBLE
            binding.cardStats.visibility = View.VISIBLE
            binding.layoutFloatingOverlay.visibility = View.GONE
            val density = resources.displayMetrics.density
            val m = (16 * density).toInt()
            lp.setMargins(m, m, m, m)
        }
        binding.stylusView.layoutParams = lp

        applySystemBars(enabled)
    }

    private fun applySystemBars(fullscreen: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                if (fullscreen) {
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            if (fullscreen) {
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
            } else {
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isFullScreen) {
            applySystemBars(true)
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
