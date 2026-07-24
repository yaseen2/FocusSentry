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

    private var listener: SessionListener? = null
    private var dbListener: ValueEventListener? = null
    private var lastEvent: String = ""

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

                override fun onCancelled(error: DatabaseError) {
                    // Ignored or logged
                }
            }

            ref.addValueEventListener(dbListener!!)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopListening() {
        try {
            if (dbListener != null) {
                val db = FirebaseDatabase.getInstance("https://gazereader-default-rtdb.firebaseio.com")
                db.getReference("session_status").removeEventListener(dbListener!!)
                dbListener = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
