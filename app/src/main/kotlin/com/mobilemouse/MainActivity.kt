package com.mobilemouse

import android.content.*
import android.graphics.Color
import android.os.*
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

        // Mode toggle button: Absolute (Tablet/Pen) vs Relative (Trackpad/Mouse)
        binding.btnModeToggle.setOnClickListener {
            val isRel = !binding.stylusView.isRelativeMode
            binding.stylusView.isRelativeMode = isRel
            if (isRel) {
                binding.btnModeToggle.text = "????: ??? (????)"
                binding.btnModeToggle.setBackgroundColor(Color.parseColor("#0F3460"))
            } else {
                binding.btnModeToggle.text = "????: ??? (????)"
                binding.btnModeToggle.setBackgroundColor(Color.parseColor("#E94560"))
            }
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
            server.broadcast(packet)
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

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
