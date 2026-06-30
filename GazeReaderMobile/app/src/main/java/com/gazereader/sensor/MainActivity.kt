package com.gazereader.sensor

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var sbSensitivity: SeekBar
    private lateinit var tvSensVal: TextView
    private lateinit var btnToggle: Button
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etIp = findViewById(R.id.etIp)
        etPort = findViewById(R.id.etPort)
        sbSensitivity = findViewById(R.id.sbSensitivity)
        tvSensVal = findViewById(R.id.tvSensVal)
        btnToggle = findViewById(R.id.btnToggle)

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

            // Save preferences
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
                btnToggle.text = "Stop Tracking"
                Toast.makeText(this, "Tracking Started", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (SensorService.isRunning) {
            btnToggle.text = "Stop Tracking"
        } else {
            btnToggle.text = "Start Tracking"
        }
    }
}
