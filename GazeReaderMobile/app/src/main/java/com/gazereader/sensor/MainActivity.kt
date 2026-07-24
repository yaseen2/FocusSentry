package com.gazereader.sensor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var sbSensitivity: SeekBar
    private lateinit var tvSensVal: TextView
    private lateinit var btnToggle: Button

    private lateinit var tvSessionPhase: TextView
    private lateinit var tvSessionTimer: TextView
    private lateinit var tvFirebaseSync: TextView
    private lateinit var btnStopAlarm: Button

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val statusUpdateRunnable = object : Runnable {
        override fun run() {
            updateSessionUI()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etIp = findViewById(R.id.etIp)
        etPort = findViewById(R.id.etPort)
        sbSensitivity = findViewById(R.id.sbSensitivity)
        tvSensVal = findViewById(R.id.tvSensVal)
        btnToggle = findViewById(R.id.btnToggle)

        tvSessionPhase = findViewById(R.id.tvSessionPhase)
        tvSessionTimer = findViewById(R.id.tvSessionTimer)
        tvFirebaseSync = findViewById(R.id.tvFirebaseSync)
        btnStopAlarm = findViewById(R.id.btnStopAlarm)

        prefs = getSharedPreferences("GazeReaderPrefs", Context.MODE_PRIVATE)

        // Load saved values
        etIp.setText(prefs.getString("laptop_ip", "192.168.1.100"))
        etPort.setText(prefs.getString("laptop_port", "5001"))
        
        val savedSensitivity = prefs.getFloat("sensitivity", 2.0f)
        sbSensitivity.progress = (savedSensitivity * 10).toInt()
        tvSensVal.text = String.format("%.1f m/s²", savedSensitivity)

        sbSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress / 10.0f
                tvSensVal.text = String.format("%.1f m/s²", value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnToggle.setOnClickListener {
            val ip = etIp.text.toString().trim()
            val port = etPort.text.toString().trim()
            val sens = sbSensitivity.progress / 10.0f

            if (ip.isEmpty() || port.isEmpty()) {
                Toast.makeText(this, "Please enter IP and Port", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit().apply {
                putString("laptop_ip", ip)
                putString("laptop_port", port)
                putFloat("sensitivity", sens)
                apply()
            }

            val isRunning = SensorService.isRunning
            val serviceIntent = Intent(this, SensorService::class.java).apply {
                putExtra("ip", ip)
                putExtra("port", port)
                putExtra("sensitivity", sens)
            }

            if (isRunning) {
                stopService(serviceIntent)
                btnToggle.text = "Start Tracking"
                Toast.makeText(this, "Tracking Stopped", Toast.LENGTH_SHORT).show()
            } else {
                ContextCompat.startForegroundService(this, serviceIntent)
                btnToggle.text = "Start Tracking"
                Toast.makeText(this, "Tracking Started", Toast.LENGTH_SHORT).show()
            }
        }

        btnStopAlarm.setOnClickListener {
            val stopIntent = Intent(this, SensorService::class.java).apply {
                action = SensorService.ACTION_STOP_ALARM
            }
            startService(stopIntent)
            btnStopAlarm.visibility = View.GONE
            Toast.makeText(this, "Alarm Stopped", Toast.LENGTH_SHORT).show()
        }

        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun updateSessionUI() {
        val status = SensorService.currentStatus
        if (SensorService.isRunning) {
            tvFirebaseSync.text = "🔥 Firebase Cloud: Synced"
            tvFirebaseSync.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))

            when (status.phase) {
                "FOCUS" -> {
                    tvSessionPhase.text = "🎯 FOCUS PHASE"
                    tvSessionPhase.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_light))
                    val min = status.time_left / 60
                    val sec = status.time_left % 60
                    tvSessionTimer.text = String.format("%02d:%02d remaining", min, sec)
                }
                "BREAK" -> {
                    tvSessionPhase.text = "☕ BREAK PHASE"
                    tvSessionPhase.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
                    val min = status.time_left / 60
                    val sec = status.time_left % 60
                    tvSessionTimer.text = String.format("%02d:%02d remaining", min, sec)
                }
                else -> {
                    tvSessionPhase.text = "⚡ READY & SYNCED"
                    tvSessionPhase.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                    tvSessionTimer.text = "Waiting for Pomodoro session..."
                }
            }
        } else {
            tvFirebaseSync.text = "⚪ Service Inactive"
            tvFirebaseSync.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            tvSessionPhase.text = "STANDBY"
            tvSessionPhase.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            tvSessionTimer.text = "--:--"
        }

        if (SensorService.isRunning) {
            btnToggle.text = "Stop Tracking"
        } else {
            btnToggle.text = "Start Tracking"
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(statusUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusUpdateRunnable)
    }
}
