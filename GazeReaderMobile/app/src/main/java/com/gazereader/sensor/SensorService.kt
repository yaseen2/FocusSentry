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

class SensorService : Service(), SensorEventListener {

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

    companion object {
        var isRunning = false
            private set
        private const val CHANNEL_ID = "GazeReaderMobileChannel"
        private const val NOTIFICATION_ID = 88
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ip = intent?.getStringExtra("ip") ?: "192.168.1.100"
        port = intent?.getStringExtra("port") ?: "5001"
        sensitivity = intent?.getFloatExtra("sensitivity", 2.0f) ?: 2.0f

        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        return START_NOT_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
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
                // Network failures are ignored in background logs
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
            .setContentTitle("GazeReader Sensor Active")
            .setContentText("Monitoring Pixel phone accelerometer pings...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
