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
            val autoStart = prefs.getBoolean("auto_start_on_boot", true)

            if (autoStart) {
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
                }
            }
        }
    }
}
