package com.mobilemouse

import android.content.*
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

        // Start and bind to the foreground service
        val intent = Intent(this, StylusServerService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

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
                binding.tvPressure.text = String.format(Locale.US, "%.0f%%", pressure * 100)
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
        val clients = server?.clientCount ?: 0
        runOnUiThread {
            binding.tvClients.text = clients.toString()
            if (clients > 0) {
                binding.tvStatus.text = "$clients PC client(s) connected"
                binding.statusDot.setBackgroundResource(R.drawable.dot_connected)
            } else {
                binding.tvStatus.text = "Waiting for PC connection..."
                binding.statusDot.setBackgroundResource(R.drawable.dot_disconnected)
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
