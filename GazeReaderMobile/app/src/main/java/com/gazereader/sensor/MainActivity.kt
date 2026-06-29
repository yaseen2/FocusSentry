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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class MainActivity : AppCompatActivity() {

    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var etFirebaseUrl: EditText
    private lateinit var etFirebasePath: EditText
    private lateinit var sbSensitivity: SeekBar
    private lateinit var tvSensVal: TextView
    private lateinit var btnToggle: Button
    private lateinit var rgMode: android.widget.RadioGroup
    private lateinit var rbLocal: android.widget.RadioButton
    private lateinit var rbCloud: android.widget.RadioButton
    private lateinit var layoutLocal: android.widget.LinearLayout
    private lateinit var layoutCloud: android.widget.LinearLayout
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etIp = findViewById(R.id.etIp)
        etPort = findViewById(R.id.etPort)
        etFirebaseUrl = findViewById(R.id.etFirebaseUrl)
        etFirebasePath = findViewById(R.id.etFirebasePath)
        sbSensitivity = findViewById(R.id.sbSensitivity)
        tvSensVal = findViewById(R.id.tvSensVal)
        btnToggle = findViewById(R.id.btnToggle)
        rgMode = findViewById(R.id.rgMode)
        rbLocal = findViewById(R.id.rbLocal)
        rbCloud = findViewById(R.id.rbCloud)
        layoutLocal = findViewById(R.id.layoutLocal)
        layoutCloud = findViewById(R.id.layoutCloud)

        prefs = getSharedPreferences("GazeReaderPrefs", Context.MODE_PRIVATE)

        // Load saved values
        etIp.setText(prefs.getString("laptop_ip", "192.168.1.100"))
        etPort.setText(prefs.getString("laptop_port", "5001"))
        etFirebaseUrl.setText(prefs.getString("firebase_url", ""))
        etFirebasePath.setText(prefs.getString("firebase_path", "yaseen"))
        
        val mode = prefs.getString("link_mode", "LOCAL")
        if (mode == "CLOUD") {
            rbCloud.isChecked = true
            layoutLocal.visibility = android.view.View.GONE
            layoutCloud.visibility = android.view.View.VISIBLE
        } else {
            rbLocal.isChecked = true
            layoutLocal.visibility = android.view.View.VISIBLE
            layoutCloud.visibility = android.view.View.GONE
        }

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbCloud) {
                layoutLocal.visibility = android.view.View.GONE
                layoutCloud.visibility = android.view.View.VISIBLE
            } else {
                layoutLocal.visibility = android.view.View.VISIBLE
                layoutCloud.visibility = android.view.View.GONE
            }
        }

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
            val isCloud = rbCloud.isChecked
            val modeVal = if (isCloud) "CLOUD" else "LOCAL"

            val ip = etIp.text.toString().trim()
            val port = etPort.text.toString().trim()
            val firebaseUrl = etFirebaseUrl.text.toString().trim()
            val firebasePath = etFirebasePath.text.toString().trim()
            val sens = sbSensitivity.progress / 10.0f

            if (isCloud) {
                if (firebaseUrl.isEmpty() || firebasePath.isEmpty()) {
                    Toast.makeText(this, "Please enter Firebase Database URL and Path", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            } else {
                if (ip.isEmpty() || port.isEmpty()) {
                    Toast.makeText(this, "Please enter IP and Port", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            // Save preferences
            prefs.edit().apply {
                putString("link_mode", modeVal)
                putString("laptop_ip", ip)
                putString("laptop_port", port)
                putString("firebase_url", firebaseUrl)
                putString("firebase_path", firebasePath)
                putFloat("sensitivity", sens)
                apply()
            }

            val isRunning = SensorService.isRunning
            val serviceIntent = Intent(this, SensorService::class.java).apply {
                putExtra("link_mode", modeVal)
                putExtra("ip", ip)
                putExtra("port", port)
                putExtra("firebase_url", firebaseUrl)
                putExtra("firebase_path", firebasePath)
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
