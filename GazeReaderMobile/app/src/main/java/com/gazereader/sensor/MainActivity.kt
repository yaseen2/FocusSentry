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
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter

class MainActivity : AppCompatActivity(), FirebaseJournalManager.JournalListener {

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
    private lateinit var pieChart: PieChart

    private lateinit var prefs: SharedPreferences
    private lateinit var journalManager: FirebaseJournalManager
    private var currentTab = "DAY"
    private var cachedJournalData = JournalData()

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
        pieChart = findViewById(R.id.pieChart)

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

        setupTabListeners()
        setupChartStyles()
        requestNotificationPermission()

        journalManager = FirebaseJournalManager()
        journalManager.startListening(this)
    }

    private fun setupTabListeners() {
        btnTabDay.setOnClickListener { selectTab("DAY") }
        btnTabWeek.setOnClickListener { selectTab("WEEK") }
        btnTabMonth.setOnClickListener { selectTab("MONTH") }
    }

    private fun selectTab(tab: String) {
        currentTab = tab
        val activeBg = Color.parseColor("#6366f1")
        val inactiveBg = Color.parseColor("#1e293b")
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
        // Configure Bar Chart Style
        barChart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setDrawGridBackground(false)
            setDrawBarShadow(false)
            setTouchEnabled(true)
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = Color.parseColor("#94a3b8")
                setDrawGridLines(false)
                granularity = 1f
            }
            axisLeft.apply {
                textColor = Color.parseColor("#94a3b8")
                setDrawGridLines(true)
                gridColor = Color.parseColor("#1e293b")
                axisMinimum = 0f
            }
            axisRight.isEnabled = false
        }

        // Configure Donut Pie Chart Style
        pieChart.apply {
            description.isEnabled = false
            isDrawHoleEnabled = true
            setHoleColor(Color.parseColor("#0b0f19"))
            setTransparentCircleColor(Color.TRANSPARENT)
            holeRadius = 55f
            setEntryLabelColor(Color.WHITE)
            setEntryLabelTextSize(10f)
            legend.apply {
                textColor = Color.parseColor("#cbd5e1")
                textSize = 10f
                isWordWrapEnabled = true
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
            val activeMin = today.active_seconds / 60
            val distMin = today.distracted_seconds / 60

            tvActiveMin.text = "${activeMin}m"
            tvDistractedMin.text = "${distMin}m"
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
                val actMin = (e.active_seconds / 60).toFloat()
                val distMin = (e.distracted_seconds / 60).toFloat()
                entries.add(BarEntry(i.toFloat(), floatArrayOf(actMin, distMin)))
                xLabels.add(e.day)
            }

            val overallEfficiency = if (totalAct + totalDist > 0) ((totalAct.toDouble() / (totalAct + totalDist)) * 100).toInt() else 100
            tvActiveMin.text = "${totalAct / 60}m"
            tvDistractedMin.text = "${totalDist / 60}m"
            tvEfficiency.text = "$overallEfficiency%"

            if (entries.isNotEmpty()) {
                val set = BarDataSet(entries, "Study History").apply {
                    colors = listOf(Color.parseColor("#6366f1"), Color.parseColor("#f43f5e"))
                    stackLabels = arrayOf("Focused", "Distracted")
                    valueTextColor = Color.TRANSPARENT
                }

                barChart.xAxis.valueFormatter = IndexAxisValueFormatter(xLabels)
                barChart.data = BarData(set)
                barChart.invalidate()
                barChart.animateY(600)
            }
        }

        // Render Distraction Donut Chart
        val pieEntries = mutableListOf<PieEntry>()
        val colors = listOf(
            Color.parseColor("#6366f1"),
            Color.parseColor("#f43f5e"),
            Color.parseColor("#f59e0b"),
            Color.parseColor("#10b981"),
            Color.parseColor("#8b5cf6")
        )

        for (d in data.distractions) {
            val label = if (d.domain_or_app.length > 20) d.domain_or_app.substring(0, 18) + ".." else d.domain_or_app
            val min = (d.total_seconds / 60).coerceAtLeast(1)
            pieEntries.add(PieEntry(min.toFloat(), label))
        }

        if (pieEntries.isNotEmpty()) {
            val pieDataSet = PieDataSet(pieEntries, "").apply {
                this.colors = colors
                valueTextColor = Color.WHITE
                valueTextSize = 10f
            }
            pieChart.data = PieData(pieDataSet)
            pieChart.invalidate()
            pieChart.animateXY(600, 600)
        }
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

    override fun onDestroy() {
        super.onDestroy()
        journalManager.stopListening()
    }
}
