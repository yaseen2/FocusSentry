package com.gazereader.sensor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

class SensorService : Service(), SensorEventListener, FirebaseSyncManager.SessionListener, FirebaseSyncManager.LaptopConfigListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var hasLastValues = false

    private var ip = ""
    private var port = ""
    private var sensitivity = 2.0f
    private var lastPingTime = 0L

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var firebaseSync: FirebaseSyncManager
    private lateinit var breakAlarmHelper: BreakAlarmHelper
    private var isBreakActive = false

    companion object {
        var isRunning = false
            private set
        var currentStatus = SessionStatus()
            private set

        const val ACTION_STOP_ALARM = "com.gazereader.sensor.ACTION_STOP_ALARM"
        private const val CHANNEL_ID = "GazeReaderMobileChannel"
        private const val NOTIFICATION_ID = 88
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        breakAlarmHelper = BreakAlarmHelper(this)

        firebaseSync = FirebaseSyncManager()
        firebaseSync.startListening(this)
        firebaseSync.startListeningConfig(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALARM) {
            breakAlarmHelper.stopAlarm()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences("GazeReaderPrefs", Context.MODE_PRIVATE)
        ip = intent?.getStringExtra("ip") ?: prefs.getString("laptop_ip", "192.168.1.100") ?: "192.168.1.100"
        port = intent?.getStringExtra("port") ?: prefs.getString("laptop_port", "5001") ?: "5001"
        sensitivity = intent?.getFloatExtra("sensitivity", 2.0f) ?: prefs.getFloat("sensitivity", 2.0f)

        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        registerAccelerometer()

        return START_NOT_STICKY
    }

    override fun onLaptopConfigUpdated(ip: String, port: String) {
        if (ip.isNotEmpty()) {
            this.ip = ip
            if (port.isNotEmpty()) {
                this.port = port
            }
            val prefs = getSharedPreferences("GazeReaderPrefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("laptop_ip", ip)
                putString("laptop_port", port)
                apply()
            }
        }
    }

    private fun registerAccelerometer() {
        accelerometer?.let {
            sensorManager.unregisterListener(this)
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun unregisterAccelerometer() {
        sensorManager.unregisterListener(this)
    }

    override fun onStatusChanged(status: SessionStatus) {
        currentStatus = status
        
        if (status.phase == "BREAK") {
            isBreakActive = true
            // Pause accelerometer during break to avoid false distraction pings
            unregisterAccelerometer()
            val nowSec = System.currentTimeMillis() / 1000L
            val elapsedSec = if (status.start_timestamp > 0L) maxOf(0L, nowSec - status.start_timestamp).toInt() else 0
            val currentRemaining = maxOf(0, status.duration - elapsedSec)
            breakAlarmHelper.updateBreakCountdownNotification(currentRemaining)
        } else {
            if (isBreakActive && status.phase == "FOCUS") {
                isBreakActive = false
                breakAlarmHelper.triggerBreakOverAlarm()
                registerAccelerometer()
            } else {
                isBreakActive = false
                breakAlarmHelper.clearBreakNotification()
                registerAccelerometer()
            }
        }
    }

    override fun onBreakEnded() {
        breakAlarmHelper.triggerBreakOverAlarm()
        registerAccelerometer()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (isBreakActive) return // Skip tracking when on break

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            if (hasLastValues) {
                val deltaX = abs(x - lastX)
                val deltaY = abs(y - lastY)
                val deltaZ = abs(z - lastZ)
                
                val magnitude = deltaX + deltaY + deltaZ

                if (magnitude > sensitivity) {
                    val now = System.currentTimeMillis()
                    if (now - lastPingTime > 1500) { // Throttle pings to 1.5s
                        lastPingTime = now
                        sendPingToLaptop()
                    }
                }
            }

            lastX = x
            lastY = y
            lastZ = z
            hasLastValues = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun sendPingToLaptop() {
        serviceScope.launch {
            try {
                val urlSpec = "http://$ip:$port/ping"
                val url = URL(urlSpec)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 800
                conn.readTimeout = 800
                conn.requestMethod = "GET"
                
                val code = conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "GazeReader Sensor Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GazeReader Active & Firebase Synced")
            .setContentText("Monitoring session timers & phone movement...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        firebaseSync.stopListening()
        breakAlarmHelper.stopAlarm()
        breakAlarmHelper.clearBreakNotification()
        unregisterAccelerometer()
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
