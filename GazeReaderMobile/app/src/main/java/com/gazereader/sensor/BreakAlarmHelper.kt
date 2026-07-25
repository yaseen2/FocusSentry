package com.gazereader.sensor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat

class BreakAlarmHelper(private val context: Context) {

    companion object {
        const val CHANNEL_BREAK_ID = "gaze_break_timer_channel"
        const val CHANNEL_ALARM_ID = "gaze_break_alarm_channel"
        const val NOTIF_BREAK_ID = 2001
        const val NOTIF_ALARM_ID = 2002

        // Static references to active audio/vibration resources to guarantee 100% reliable stopping across instances
        private var activeRingtone: Ringtone? = null
        private var activeVibrator: Vibrator? = null
        private val handler = Handler(Looper.getMainLooper())
        private var autoStopRunnable: Runnable? = null

        fun stopActiveAlarm(context: Context) {
            try {
                autoStopRunnable?.let { handler.removeCallbacks(it) }
                autoStopRunnable = null

                activeRingtone?.let {
                    if (it.isPlaying) {
                        it.stop()
                    }
                }
                activeRingtone = null
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                activeVibrator?.cancel()
                activeVibrator = null
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(NOTIF_ALARM_ID)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Silent/Low-priority channel for break countdown updates
            val breakChannel = NotificationChannel(
                CHANNEL_BREAK_ID,
                "GazeReader Break Countdown",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live remaining break time"
            }

            // 2. High-priority channel with audio attributes for Break-Over Alarm
            val alarmChannel = NotificationChannel(
                CHANNEL_ALARM_ID,
                "GazeReader Break Alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Fires loud alarm when break time finishes"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 400, 800, 400)
            }

            manager.createNotificationChannel(breakChannel)
            manager.createNotificationChannel(alarmChannel)
        }
    }

    fun updateBreakCountdownNotification(timeLeftSeconds: Int) {
        val min = timeLeftSeconds / 60
        val sec = timeLeftSeconds % 60
        val timeStr = String.format("%02d:%02d", min, sec)

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_BREAK_ID)
            .setContentTitle("☕ Break Time Active")
            .setContentText("Remaining Break: $timeStr")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_BREAK_ID, notif)
    }

    fun triggerBreakOverAlarm() {
        // Stop any currently playing alarm before starting a new one
        stopActiveAlarm(context)

        // Clear countdown notification
        clearBreakNotification()

        // 1. Play Alarm Ringtone
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            activeRingtone = RingtoneManager.getRingtone(context, alarmUri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Trigger Controlled Vibration Pattern
        try {
            activeVibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeVibrator?.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 800, 400, 800, 400, 800, 400), 0
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                activeVibrator?.vibrate(longArrayOf(0, 800, 400, 800, 400, 800, 400), 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Safety Auto-Stop Timeout (Automatically silences after 25 seconds if unattended)
        autoStopRunnable = Runnable {
            stopActiveAlarm(context)
        }
        handler.postDelayed(autoStopRunnable!!, 25000L)

        // 4. Intent & PendingIntents for Stopping Alarm via Tap, Action Button, or Swipe-to-Dismiss
        val stopIntent = Intent(context, AlarmStopReceiver::class.java)
        val pendingStopIntent = PendingIntent.getBroadcast(
            context, 101, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val activityIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("STOP_ALARM_ON_OPEN", true)
        }
        val pendingActivityIntent = PendingIntent.getActivity(
            context, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ALARM_ID)
            .setContentTitle("🚨 BREAK IS OVER!")
            .setContentText("Return to your desk! Focus session starting.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingActivityIntent)
            .setDeleteIntent(pendingStopIntent) // Swiping notification away stops the alarm instantly
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "⏹️ STOP ALARM", pendingStopIntent) // Action button
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ALARM_ID, notif)
    }

    fun stopAlarm() {
        stopActiveAlarm(context)
    }

    fun clearBreakNotification() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIF_BREAK_ID)
    }
}
