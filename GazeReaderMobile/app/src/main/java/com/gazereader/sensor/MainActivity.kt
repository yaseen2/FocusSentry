package com.gazereader.sensor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
                btnToggle.text = "Start Tracking"
                Toast.makeText(this, "Tracking Stopped", Toast.LENGTH_SHORT).show()
            } else {
                ContextCompat.startForegroundService(this, serviceIntent)
                btnToggle.text = "Start Tracking"
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
                        Toast.makeText(this@MainActivity, "⚡ Auto-Synced Laptop IP: $ip", Toast.LENGTH_SHORT).show()
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

    private var sessionLatencyOffsetMs = 0L
    private var lastEventReceiveMs = 0L

    override fun onStatusChanged(status: SessionStatus) {
        liveSessionStatus = status
        val receiveMs = System.currentTimeMillis()
        val startMs = if (status.start_timestamp < 100000000000L) status.start_timestamp * 1000L else status.start_timestamp
        
        if (status.event.contains("STARTED") || status.event.contains("ENDED")) {
            lastEventReceiveMs = receiveMs
            val transitLatencyMs = maxOf(0L, receiveMs - startMs)
            if (transitLatencyMs < 4000L) {
                sessionLatencyOffsetMs = transitLatencyMs
            } else {
                sessionLatencyOffsetMs = 0L
            }
        }
        runOnUiThread { updateSessionUI() }
    }

    override fun onBreakEnded() {}

    private fun setupTabListeners() {
        btnTabDay.setOnClickListener { selectTab("DAY") }
        btnTabWeek.setOnClickListener { selectTab("WEEK") }
        btnTabMonth.setOnClickListener { selectTab("MONTH") }
    }

    private fun selectTab(tab: String) {
        currentTab = tab
        val activeBg = Color.parseColor("#6366f1")
        val inactiveBg = Color.parseColor("#121829")
        val activeTextColor = Color.parseColor("#ffffff")
        val inactiveTextColor = Color.parseColor("#94a3b8")

        btnTabDay.setBackgroundColor(if (tab == "DAY") activeBg else inactiveBg)
        btnTabDay.setTextColor(if (tab == "DAY") activeTextColor else inactiveTextColor)

        btnTabWeek.setBackgroundColor(if (tab == "WEEK") activeBg else inactiveBg)
        btnTabWeek.setTextColor(if (tab == "WEEK") activeTextColor else inactiveTextColor)

        btnTabMonth.setBackgroundColor(if (tab == "MONTH") activeBg else inactiveBg)
        btnTabMonth.setTextColor(if (tab == "MONTH") activeTextColor else inactiveTextColor)

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
        val targetLine = com.github.mikephil.charting.components.LimitLine(9 * 3600f, "9h Goal 🎯").apply {
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

            focusCircleView.setProgress(today.active_seconds, today.target_seconds)

            val remSec = today.target_seconds - today.active_seconds
            if (remSec > 0) {
                val remH = remSec / 3600
                val remM = (remSec % 3600) / 60
                val timeStr = if (remH > 0) "${remH}h ${remM}m" else "${remM}m"
                tvQuestNotice.text = "Quest status: Focus for $timeStr more today to reach your 9h target! 🎯"
                tvQuestNotice.setTextColor(Color.parseColor("#6366f1"))
            } else {
                tvQuestNotice.text = "Quest completed! You reached your daily 9-hour focus target! 🏆"
                tvQuestNotice.setTextColor(Color.parseColor("#10b981"))
            }
        } else {
            tvQuestNotice.visibility = View.GONE
            focusCircleView.visibility = View.GONE
            barChart.visibility = View.VISIBLE

            val history = if (currentTab == "WEEK") data.weekly else data.monthly
            var totalAct = 0
            var totalDist = 0
            val entries = mutableListOf<BarEntry>()
            val xLabels = mutableListOf<String>()

            for (i in history.indices) {
                val e = history[i]
                totalAct += e.active_seconds
                totalDist += e.distracted_seconds
                entries.add(BarEntry(i.toFloat(), floatArrayOf(e.active_seconds.toFloat(), e.distracted_seconds.toFloat())))
                xLabels.add(e.day)
            }

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
                    override fun getFormattedValue(value: Float): String {
                        val sec = value.toInt()
                        if (sec <= 0) return ""
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

        if (isActive) {
            tvFirebaseSync.text = "🔥 Cloud Synced"
            tvFirebaseSync.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))

            val nowMs = System.currentTimeMillis()
            val startMs = if (status.start_timestamp < 100000000000L) status.start_timestamp * 1000L else status.start_timestamp
            
            val currentRemaining = if ((status.event.contains("STARTED") || status.event.contains("ENDED")) && (nowMs - lastEventReceiveMs < 1200L)) {
                status.duration
            } else {
                val adjustedNowMs = maxOf(startMs, nowMs - sessionLatencyOffsetMs)
                val elapsedSec = if (startMs > 0L) maxOf(0L, (adjustedNowMs - startMs) / 1000L).toInt() else 0
                maxOf(0, status.duration - elapsedSec)
            }

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
