package com.gazereader.sensor

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

data class TodayMetrics(
    val active_seconds: Int = 0,
    val distracted_seconds: Int = 0,
    val target_seconds: Int = 9 * 3600,
    val efficiency: Int = 100,
    val last_updated: Long = 0L
)

data class DayHistoryEntry(
    val day: String = "",
    val active_seconds: Int = 0,
    val distracted_seconds: Int = 0
)

data class DistractionEntry(
    val domain_or_app: String = "",
    val total_seconds: Int = 0
)

data class JournalData(
    val today: TodayMetrics = TodayMetrics(),
    val weekly: List<DayHistoryEntry> = emptyList(),
    val monthly: List<DayHistoryEntry> = emptyList(),
    val distractions: List<DistractionEntry> = emptyList()
)

class FirebaseJournalManager {

    interface JournalListener {
        fun onJournalDataUpdated(data: JournalData)
    }

    private var dbListener: ValueEventListener? = null

    fun startListening(listener: JournalListener) {
        try {
            val db = FirebaseDatabase.getInstance("https://gazereader-default-rtdb.firebaseio.com")
            val ref = db.getReference("journal")
            ref.keepSynced(true)

            dbListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) return

                    // Parse Today
                    val todaySnap = snapshot.child("today")
                    val today = TodayMetrics(
                        active_seconds = (todaySnap.child("active_seconds").getValue(Long::class.java) ?: 0L).toInt(),
                        distracted_seconds = (todaySnap.child("distracted_seconds").getValue(Long::class.java) ?: 0L).toInt(),
                        target_seconds = (todaySnap.child("target_seconds").getValue(Long::class.java) ?: 32400L).toInt(),
                        efficiency = (todaySnap.child("efficiency").getValue(Long::class.java) ?: 100L).toInt(),
                        last_updated = todaySnap.child("last_updated").getValue(Long::class.java) ?: 0L
                    )

                    // Parse Weekly
                    val weeklyList = mutableListOf<DayHistoryEntry>()
                    for (child in snapshot.child("weekly").children) {
                        val day = child.child("day").getValue(String::class.java) ?: ""
                        val act = (child.child("active_seconds").getValue(Long::class.java) ?: 0L).toInt()
                        val dist = (child.child("distracted_seconds").getValue(Long::class.java) ?: 0L).toInt()
                        weeklyList.add(DayHistoryEntry(day, act, dist))
                    }

                    // Parse Monthly
                    val monthlyList = mutableListOf<DayHistoryEntry>()
                    for (child in snapshot.child("monthly").children) {
                        val day = child.child("day").getValue(String::class.java) ?: ""
                        val act = (child.child("active_seconds").getValue(Long::class.java) ?: 0L).toInt()
                        val dist = (child.child("distracted_seconds").getValue(Long::class.java) ?: 0L).toInt()
                        monthlyList.add(DayHistoryEntry(day, act, dist))
                    }

                    // Parse Distractions
                    val distractionList = mutableListOf<DistractionEntry>()
                    for (child in snapshot.child("distractions").children) {
                        val domain = child.child("domain_or_app").getValue(String::class.java) ?: ""
                        val total = (child.child("total_seconds").getValue(Long::class.java) ?: 0L).toInt()
                        distractionList.add(DistractionEntry(domain, total))
                    }

                    val journalData = JournalData(today, weeklyList, monthlyList, distractionList)
                    listener.onJournalDataUpdated(journalData)
                }

                override fun onCancelled(error: DatabaseError) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try { ref.addValueEventListener(this) } catch (e: Exception) {}
                    }, 3000)
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
                db.getReference("journal").removeEventListener(dbListener!!)
                dbListener = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
