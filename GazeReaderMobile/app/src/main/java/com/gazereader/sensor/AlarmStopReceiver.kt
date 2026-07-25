package com.gazereader.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        BreakAlarmHelper.stopActiveAlarm(context)
    }
}
