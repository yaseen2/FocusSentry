package com.gazereader.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || 
            action == "android.intent.action.QUICKBOOT_POWERON" || 
            action == "com.htc.intent.action.QUICKBOOT_POWERON") {

            val prefs = context.getSharedPreferences("GazeReaderPrefs", Context.MODE_PRIVATE)
            val wasTrackingActive = prefs.getBoolean("was_tracking_active", false)

            // ONLY auto-start tracking if tracking was active before power off/reboot
            if (wasTrackingActive) {
                val ip = prefs.getString("laptop_ip", "192.168.1.100") ?: "192.168.1.100"
                val port = prefs.getString("laptop_port", "5001") ?: "5001"
                val sens = prefs.getFloat("sensitivity", 2.0f)

                val serviceIntent = Intent(context, SensorService::class.java).apply {
                    putExtra("ip", ip)
                    putExtra("port", port)
                    putExtra("sensitivity", sens)
                }

                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    try {
                        val launchIntent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra("AUTO_START_TRACKING", true)
                        }
                        context.startActivity(launchIntent)
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                }
            }
        }
    }
}
