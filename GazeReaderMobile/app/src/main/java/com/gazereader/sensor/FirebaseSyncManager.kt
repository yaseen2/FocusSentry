package com.gazereader.sensor

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

data class SessionStatus(
    val active: Boolean = false,
    val phase: String = "INACTIVE",
    val time_left: Int = 0,
    val event: String = "",
    val timestamp: Long = 0L
)

class FirebaseSyncManager {

    interface SessionListener {
        fun onStatusChanged(status: SessionStatus)
        fun onBreakEnded()
    }

    interface LaptopConfigListener {
        fun onLaptopConfigUpdated(ip: String, port: String)
    }

    interface HotspotCommandListener {
        fun onHotspotCommandReceived(command: String)
    }

    private var listener: SessionListener? = null
    private var configListener: LaptopConfigListener? = null
    private var hotspotListener: HotspotCommandListener? = null

    private var dbListener: ValueEventListener? = null
    private var configDbListener: ValueEventListener? = null
    private var hotspotDbListener: ValueEventListener? = null

    private var lastEvent: String = ""
    private var lastHotspotTimestamp: Long = 0L

    fun startListening(listener: SessionListener) {
        this.listener = listener
        try {
            val db = FirebaseDatabase.getInstance("https://gazereader-default-rtdb.firebaseio.com")
            val ref = db.getReference("session_status")

            dbListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) return

                    val active = snapshot.child("active").getValue(Boolean::class.java) ?: false
                    val phase = snapshot.child("phase").getValue(String::class.java) ?: "INACTIVE"
                    val timeLeft = (snapshot.child("time_left").getValue(Long::class.java) ?: 0L).toInt()
                    val event = snapshot.child("event").getValue(String::class.java) ?: ""
                    val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L

                    val status = SessionStatus(active, phase, timeLeft, event, timestamp)
                    listener.onStatusChanged(status)

                    if (event == "BREAK_ENDED" && lastEvent != "BREAK_ENDED") {
                        listener.onBreakEnded()
                    }
                    lastEvent = event
                }

                override fun onCancelled(error: DatabaseError) {}
            }

            ref.addValueEventListener(dbListener!!)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startListeningConfig(configListener: LaptopConfigListener) {
        this.configListener = configListener
        try {
            val db = FirebaseDatabase.getInstance("https://gazereader-default-rtdb.firebaseio.com")
            val ref = db.getReference("laptop_config")

            configDbListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) return

                    val ip = snapshot.child("ip").getValue(String::class.java) ?: ""
                    val port = (snapshot.child("port").getValue(Long::class.java) ?: 5001L).toString()

                    if (ip.isNotEmpty()) {
                        configListener.onLaptopConfigUpdated(ip, port)
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            }

            ref.addValueEventListener(configDbListener!!)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun startListeningHotspotCommand(hotspotListener: HotspotCommandListener) {
        this.hotspotListener = hotspotListener
        try {
            val db = FirebaseDatabase.getInstance("https://gazereader-default-rtdb.firebaseio.com")
            val ref = db.getReference("hotspot_command")

            hotspotDbListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) return

                    val command = snapshot.child("action").getValue(String::class.java) ?: ""
                    val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L

                    if (command.isNotEmpty() && timestamp > lastHotspotTimestamp) {
                        lastHotspotTimestamp = timestamp
                        hotspotListener.onHotspotCommandReceived(command)
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            }

            ref.addValueEventListener(hotspotDbListener!!)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopListening() {
        try {
            val db = FirebaseDatabase.getInstance("https://gazereader-default-rtdb.firebaseio.com")
            if (dbListener != null) {
                db.getReference("session_status").removeEventListener(dbListener!!)
                dbListener = null
            }
            if (configDbListener != null) {
                db.getReference("laptop_config").removeEventListener(configDbListener!!)
                configDbListener = null
            }
            if (hotspotDbListener != null) {
                db.getReference("hotspot_command").removeEventListener(hotspotDbListener!!)
                hotspotDbListener = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
