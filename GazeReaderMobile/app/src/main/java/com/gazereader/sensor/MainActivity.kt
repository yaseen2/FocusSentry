package com.gazereader.sensor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
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
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter

class MainActivity : AppCompatActivity(), FirebaseJournalManager.JournalListener, FirebaseSyncManager.SessionListener {

    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var sbSensitivity: SeekBar
    private lateinit var tvSensVal: TextView
    private lateinit var btnToggle: Button

    private lateinit var tvSessionPhase: TextView
    private lateinit var tvSessionTimer: TextView
    private lateinit var tvFirebaseSync: TextView
    private lateinit var btnStopAlarm: Button

    // Analytics Controls & Views
    private lateinit var btnTabDay: Button
    private lateinit var btnTabWeek: Button
    private lateinit var btnTabMonth: Button
    private lateinit var tvActiveMin: TextView
    private lateinit var tvDistractedMin: TextView
    private lateinit var tvEfficiency: TextView
    private lateinit var tvFocusDebt: TextView
    private lateinit var tvQuestNotice: TextView
    private lateinit var focusCircleView: MobileFocusCircleView
    private lateinit var barChart: BarChart

    private lateinit var prefs: SharedPreferences
    private lateinit var journalManager: FirebaseJournalManager
    private lateinit var syncManager: FirebaseSyncManager
    private var currentTab = "DAY"
    private var cachedJournalData = JournalData()

    private var liveSessionStatus = SessionStatus()
    private var lastStatusTimeMs = 0L

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

        // Analytics UI references
        btnTabDay = findViewById(R.id.btnTabDay)
        btnTabWeek = findViewById(R.id.btnTabWeek)
        btnTabMonth = findViewById(R.id.btnTabMonth)
        tvActiveMin = findViewById(R.id.tvActiveMin)
        tvDistractedMin = findViewById(R.id.tvDistractedMin)
        tvEfficiency = findViewById(R.id.tvEfficiency)
        tvFocusDebt = findViewById(R.id.tvFocusDebt)
        tvQuestNotice = findViewById(R.id.tvQuestNotice)
        focusCircleView = findViewById(R.id.focusCircleView)
        barChart = findViewById(R.id.barChart)

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
                prefs.edit().putBoolean("was_tracking_active", false).apply()
                btnToggle.text = "Start Tracking"
                Toast.makeText(this, "Tracking Stopped", Toast.LENGTH_SHORT).show()
            } else {
                ContextCompat.startForegroundService(this, serviceIntent)
                prefs.edit().putBoolean("was_tracking_active", true).apply()
                btnToggle.text = "Stop Tracking"
                Toast.makeText(this, "Tracking Started", Toast.LENGTH_SHORT).show()
            }
        }

        btnStopAlarm.setOnClickListener {
            BreakAlarmHelper.stopActiveAlarm(this)
            val stopIntent = Intent(this, SensorService::class.java).apply {
                action = SensorService.ACTION_STOP_ALARM
            }
            startService(stopIntent)
            btnStopAlarm.visibility = View.GONE
            Toast.makeText(this, "Alarm Stopped", Toast.LENGTH_SHORT).show()
        }

        setupTabListeners()
        setupChartStyles()
        requestNotificationPermission()

        journalManager = FirebaseJournalManager()
        journalManager.startListening(this)

        syncManager = FirebaseSyncManager()
        syncManager.startListening(this)
        syncManager.startListeningConfig(object : FirebaseSyncManager.LaptopConfigListener {
            override fun onLaptopConfigUpdated(ip: String, port: String) {
                runOnUiThread {
                    val currentIp = etIp.text.toString().trim()
                    if (currentIp != ip && ip.isNotEmpty()) {
                        etIp.setText(ip)
                        etPort.setText(port)
                        prefs.edit().apply {
                            putString("laptop_ip", ip)
                            putString("laptop_port", port)
                            apply()
                        }
                        Toast.makeText(this@MainActivity, "Auto-synced laptop IP: $ip", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("STOP_ALARM_ON_OPEN", false) == true) {
            BreakAlarmHelper.stopActiveAlarm(this)
        }
    }

    private var localTargetEndMs = 0L

    override fun onStatusChanged(status: SessionStatus) {
        liveSessionStatus = status
        
        runOnUiThread {
            if (status.event == "FOCUS_STARTED" || status.event == "BREAK_STARTED") {
                val durationMs = (status.time_left.takeIf { it > 0 } ?: status.duration) * 1000L
                localTargetEndMs = System.currentTimeMillis() + durationMs
            } else if (status.event == "SYNC_HEARTBEAT" && status.time_left > 0) {
                val expectedTargetEndMs = System.currentTimeMillis() + (status.time_left * 1000L)
                if (kotlin.math.abs(localTargetEndMs - expectedTargetEndMs) >= 2000L) {
                    localTargetEndMs = expectedTargetEndMs
                }
            } else if (status.event == "PAUSED" || status.event == "RESUMED" || status.event == "STOPPED") {
                localTargetEndMs = 0L
            }

            if (status.active) {
                tvSessionPhase.text = "${status.phase} PHASE"
                tvSessionPhase.setTextColor(if (status.phase == "FOCUS") Color.parseColor("#6366f1") else Color.parseColor("#94a3b8"))
                
                var displaySec = status.time_left
                if (localTargetEndMs > 0L) {
                    val remainingMs = localTargetEndMs - System.currentTimeMillis()
                    displaySec = (remainingMs / 1000L).coerceAtLeast(0L).toInt()
                }
                val m = displaySec / 60
                val s = displaySec % 60
                tvSessionTimer.text = String.format("%02d:%02d remaining", m, s)
            } else {
                tvSessionPhase.text = "STANDBY"
                tvSessionPhase.setTextColor(Color.parseColor("#f8fafc"))
                tvSessionTimer.text = "Waiting for Pomodoro session..."
            }
        }
    }

    override fun onBreakEnded() {}

    private fun setupTabListeners() {
        btnTabDay.setOnClickListener { switchTab("DAY") }
        btnTabWeek.setOnClickListener { switchTab("WEEK") }
        btnTabMonth.setOnClickListener { switchTab("MONTH") }
    }

    private fun switchTab(tab: String) {
        currentTab = tab
        btnTabDay.backgroundTintList = ColorStateList.valueOf(if (tab == "DAY") Color.parseColor("#6366f1") else Color.parseColor("#121829"))
        btnTabDay.setTextColor(if (tab == "DAY") Color.WHITE else Color.parseColor("#94a3b8"))

        btnTabWeek.backgroundTintList = ColorStateList.valueOf(if (tab == "WEEK") Color.parseColor("#6366f1") else Color.parseColor("#121829"))
        btnTabWeek.setTextColor(if (tab == "WEEK") Color.WHITE else Color.parseColor("#94a3b8"))

        btnTabMonth.backgroundTintList = ColorStateList.valueOf(if (tab == "MONTH") Color.parseColor("#6366f1") else Color.parseColor("#121829"))
        btnTabMonth.setTextColor(if (tab == "MONTH") Color.WHITE else Color.parseColor("#94a3b8"))

        renderJournalVisuals()
    }

    private fun setupChartStyles() {
        barChart.description.isEnabled = false
        barChart.setDrawGridBackground(false)
        barChart.setDrawBarShadow(false)
        barChart.isHighlightFullBarEnabled = false
        barChart.legend.isEnabled = false
        barChart.setPinchZoom(true)
        barChart.setScaleEnabled(true)
        barChart.isDoubleTapToZoomEnabled = false

        val xAxis = barChart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(false)
        xAxis.textColor = Color.parseColor("#94a3b8")
        xAxis.granularity = 1f

        val yAxisLeft = barChart.axisLeft
        yAxisLeft.setDrawGridLines(true)
        yAxisLeft.gridColor = Color.parseColor("#1e293b")
        yAxisLeft.textColor = Color.parseColor("#94a3b8")
        yAxisLeft.axisMinimum = 0f
        yAxisLeft.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val hours = value / 3600f
                return String.format("%.1fh", hours)
            }
        }

        // Emerald Green Dashed 9-Hour Target Line
        val targetLine = com.github.mikephil.charting.components.LimitLine(9 * 3600f, "9h Goal").apply {
            lineColor = Color.parseColor("#10b981")
            lineWidth = 1.5f
            enableDashedLine(12f, 8f, 0f)
            textColor = Color.parseColor("#10b981")
            textSize = 10f
        }
        yAxisLeft.addLimitLine(targetLine)
        yAxisLeft.setDrawLimitLinesBehindData(true)

        val yAxisRight = barChart.axisRight
        yAxisRight.isEnabled = false
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    override fun onJournalDataUpdated(data: JournalData) {
        cachedJournalData = data
        GazeReaderWidget.saveAndRefreshWidget(this@MainActivity, data.today)
        runOnUiThread { renderJournalVisuals() }
    }

    private fun renderJournalVisuals() {
        val data = cachedJournalData

        if (currentTab == "DAY") {
            tvQuestNotice.visibility = View.VISIBLE
            focusCircleView.visibility = View.VISIBLE
            barChart.visibility = View.GONE

            val today = data.today
            val actStr = if (today.active_seconds >= 3600) "${today.active_seconds / 3600}h ${(today.active_seconds % 3600) / 60}m" else "${today.active_seconds / 60}m"
            val distStr = if (today.distracted_seconds >= 3600) "${today.distracted_seconds / 3600}h ${(today.distracted_seconds % 3600) / 60}m" else "${today.distracted_seconds / 60}m"

            tvActiveMin.text = actStr
            tvDistractedMin.text = distStr
            tvEfficiency.text = "${today.efficiency}%"

            if (today.debt_seconds > 0) {
                val dh = today.debt_seconds / 3600
                val dm = (today.debt_seconds % 3600) / 60
                tvFocusDebt.text = if (dm > 0) "${dh}h ${dm}m" else "${dh}h"
                tvFocusDebt.setTextColor(Color.parseColor("#f59e0b"))
            } else {
                tvFocusDebt.text = "0m"
                tvFocusDebt.setTextColor(Color.parseColor("#10b981"))
            }

            focusCircleView.setProgress(today.active_seconds, today.target_seconds)

            val remSec = today.target_seconds - today.active_seconds
            if (remSec > 0) {
                val remH = remSec / 3600
                val remM = (remSec % 3600) / 60
                val timeStr = if (remH > 0) "${remH}h ${remM}m" else "${remM}m"
                if (today.debt_seconds > 0) {
                    tvQuestNotice.text = "Quest status: Focus for $timeStr more today to pay off debt (${today.debt_formatted}) and reach your goal!"
                    tvQuestNotice.setTextColor(Color.parseColor("#f59e0b"))
                } else {
                    tvQuestNotice.text = "Quest status: Focus for $timeStr more today to reach your 9h target!"
                    tvQuestNotice.setTextColor(Color.parseColor("#6366f1"))
                }
            } else {
                tvQuestNotice.text = "Quest completed! You reached today's focus target!"
                tvQuestNotice.setTextColor(Color.parseColor("#10b981"))
            }
        } else {
            tvQuestNotice.visibility = View.GONE
            focusCircleView.visibility = View.GONE
            barChart.visibility = View.VISIBLE

            val history = if (currentTab == "WEEK") data.weekly else data.monthly
            var totalAct = 0
            var totalDist = 0
            var maxBarVal = 9 * 3600f
            val entries = mutableListOf<BarEntry>()
            val xLabels = mutableListOf<String>()

            for (i in history.indices) {
                val e = history[i]
                totalAct += e.active_seconds
                totalDist += e.distracted_seconds
                val barSum = (e.active_seconds + e.distracted_seconds).toFloat()
                if (barSum > maxBarVal) maxBarVal = barSum
                entries.add(BarEntry(i.toFloat(), floatArrayOf(e.active_seconds.toFloat(), e.distracted_seconds.toFloat())))
                xLabels.add(e.day)
            }

            barChart.axisLeft.axisMaximum = maxBarVal * 1.15f

            val actStr = if (totalAct >= 3600) "${totalAct / 3600}h ${(totalAct % 3600) / 60}m" else "${totalAct / 60}m"
            val distStr = if (totalDist >= 3600) "${totalDist / 3600}h ${(totalDist % 3600) / 60}m" else "${totalDist / 60}m"
            val eff = if (totalAct + totalDist > 0) (totalAct * 100) / (totalAct + totalDist) else 100

            tvActiveMin.text = actStr
            tvDistractedMin.text = distStr
            tvEfficiency.text = "$eff%"

            val dataSet = BarDataSet(entries, "Study History").apply {
                colors = listOf(Color.parseColor("#6366f1"), Color.parseColor("#f43f5e"))
                stackLabels = arrayOf("Focused", "Distracted")
                valueTextColor = Color.parseColor("#e2e8f0")
                valueTextSize = 9f
                valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                    override fun getBarStackedLabel(value: Float, stackedEntry: BarEntry?): String {
                        if (value < 600f) return "" // Hide labels for segments under 10m to prevent text overlap
                        val total = stackedEntry?.yVals?.sum() ?: value
                        if (total > 0f && (value / total) < 0.25f && (stackedEntry?.yVals?.size ?: 0) > 1) {
                            return "" // Hide small secondary stack label if it would collide with primary label
                        }
                        val sec = value.toInt()
                        val h = sec / 3600
                        val m = (sec % 3600) / 60
                        return if (h > 0) "${h}h ${m}m" else "${m}m"
                    }

                    override fun getFormattedValue(value: Float): String {
                        val sec = value.toInt()
                        if (sec < 600) return ""
                        val h = sec / 3600
                        val m = (sec % 3600) / 60
                        return if (h > 0) "${h}h ${m}m" else "${m}m"
                    }
                }
            }

            barChart.xAxis.valueFormatter = IndexAxisValueFormatter(xLabels)
            barChart.data = BarData(dataSet)
            barChart.setVisibleXRangeMaximum(7f)
            if (entries.isNotEmpty()) {
                barChart.moveViewToX((entries.size - 1).toFloat())
            }
            barChart.invalidate()
        }
    }

    private fun updateSessionUI() {
        val status = if (liveSessionStatus.active) liveSessionStatus else SensorService.currentStatus
        val isActive = liveSessionStatus.active || SensorService.currentStatus.active

        if (isActive && localTargetEndMs > 0L) {
            val nowMs = System.currentTimeMillis()
            val isPCOffline = status.last_updated_ms > 0L && (nowMs - status.last_updated_ms > 360000L)
            
            if (isPCOffline) {
                tvFirebaseSync.text = "⚪ PC Disconnected"
                tvFirebaseSync.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            } else {
                tvFirebaseSync.text = "🔥 Cloud Synced (5m Heartbeat)"
                tvFirebaseSync.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            }

            val remainingMs = maxOf(0L, localTargetEndMs - nowMs)
            val currentRemaining = (remainingMs / 1000L).toInt()

            when (status.phase) {
                "FOCUS" -> {
                    tvSessionPhase.text = "🎯 FOCUS PHASE"
                    tvSessionPhase.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_light))
                    val min = currentRemaining / 60
                    val sec = currentRemaining % 60
                    tvSessionTimer.text = String.format("%02d:%02d remaining", min, sec)
                }
                "BREAK" -> {
                    tvSessionPhase.text = "☕ BREAK PHASE"
                    tvSessionPhase.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
                    val min = currentRemaining / 60
                    val sec = currentRemaining % 60
                    tvSessionTimer.text = String.format("%02d:%02d remaining", min, sec)
                }
                else -> {
                    tvSessionPhase.text = "⚡ READY & SYNCED"
                    tvSessionPhase.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                    tvSessionTimer.text = "Waiting for Pomodoro session..."
                }
            }
        } else {
            tvFirebaseSync.text = if (SensorService.isRunning) "⚡ Ready" else "⚪ Service Inactive"
            tvFirebaseSync.setTextColor(ContextCompat.getColor(this, if (SensorService.isRunning) android.R.color.holo_blue_light else android.R.color.darker_gray))
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
        BreakAlarmHelper.stopActiveAlarm(this)
        handler.post(statusUpdateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusUpdateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        journalManager.stopListening()
        syncManager.stopListening()
    }
}
