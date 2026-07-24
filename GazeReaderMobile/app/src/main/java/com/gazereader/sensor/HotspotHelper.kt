package com.gazereader.sensor

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper

class HotspotHelper(private val context: Context) {

    fun setHotspotEnabled(enable: Boolean) {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                
                // Try TetheringManager / ConnectivityManager reflection (works with WRITE_SECURE_SETTINGS)
                var success = false

                try {
                    val tm = context.getSystemService("tethering")
                    if (tm != null) {
                        if (enable) {
                            val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
                            val dummyCallback = java.lang.reflect.Proxy.newProxyInstance(
                                callbackClass.classLoader,
                                arrayOf(callbackClass)
                            ) { _, _, _ -> null }

                            val startMethod = tm.javaClass.getDeclaredMethod(
                                "startTethering",
                                Int::class.javaPrimitiveType,
                                java.util.concurrent.Executor::class.java,
                                callbackClass
                            )
                            startMethod.isAccessible = true
                            startMethod.invoke(tm, 0, java.util.concurrent.Executors.newSingleThreadExecutor(), dummyCallback)
                            success = true
                        } else {
                            val stopMethod = tm.javaClass.getDeclaredMethod("stopTethering", Int::class.javaPrimitiveType)
                            stopMethod.isAccessible = true
                            stopMethod.invoke(tm, 0)
                            success = true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (!success) {
                    try {
                        val stopStartMethod = cm.javaClass.getDeclaredMethod(
                            if (enable) "startTethering" else "stopTethering",
                            Int::class.javaPrimitiveType
                        )
                        stopStartMethod.isAccessible = true
                        stopStartMethod.invoke(cm, 0)
                        success = true
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (!success) {
                    // Fallback: Launch Tethering Settings screen directly for 1-tap toggle
                    val intent = Intent("android.settings.TETHER_SETTINGS").apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
