package com.example.api

import android.content.Context
import android.util.Log
import com.example.api.FirebaseConfig
import com.example.api.DevicePresenceManager
import com.example.util.TimeEngine
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object WeeklyStatsUpdater {
    private const val TAG = "WeeklyStatsUpdater"

    fun getYearAndWeekNumber(timestampMs: Long): String {
        val cal = Calendar.getInstance(Locale.US)
        cal.timeInMillis = timestampMs
        cal.minimalDaysInFirstWeek = 4
        cal.firstDayOfWeek = Calendar.MONDAY
        val year = cal.get(Calendar.YEAR)
        val weekNo = cal.get(Calendar.WEEK_OF_YEAR)
        return "${year}_W${String.format(Locale.US, "%02d", weekNo)}"
    }

    suspend fun updateWeeklyStats(
        context: Context,
        email: String,
        focusDurationMs: Long,
        currentTag: String
    ) {
        if (email.isBlank()) {
            Log.d(TAG, "Empty email, skipping weekly stats update.")
            return
        }

        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) {
                Log.e(TAG, "Database URL is empty, skipping weekly stats update.")
                return
            }

            val database = FirebaseDatabase.getInstance(dbUrl)
            val sanitizedEmail = DevicePresenceManager.sanitizeEmail(email)
            
            val trueTime = TimeEngine.getTrueTimeMs()
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val todayStr = sdf.format(Date(trueTime))

            // Get current display name and emoji from app_prefs
            val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val currentUsername = appPrefs.getString("current_username", "Guest") ?: "Guest"
            val cachedNickname = appPrefs.getString("user_nickname_$currentUsername", "") ?: ""
            val cachedName = appPrefs.getString("user_name_$currentUsername", "") ?: ""
            val displayName = if (cachedNickname.isNotEmpty()) cachedNickname else if (cachedName.isNotEmpty()) cachedName else currentUsername
            val cachedEmoji = appPrefs.getString("user_emoji_$currentUsername", "") ?: ""

            // Query local Room database for the consistency streak index
            val db = com.example.data.AppDatabase.getInstance(context)
            val allLocalRecords = db.localHistoryVaultDao().getAllHistoryDirect()
            val activeStreak = AnalyticsVaultEngine.calculateDailyConsistencyStreak(context, allLocalRecords)
            Log.d(TAG, "Calculated streak for weekly stats updater: $activeStreak")

            // Filter lists for different periods
            // 1. Today
            val todayRecords = allLocalRecords.filter { it.date_string == todayStr }
            val todayFocusMs = todayRecords.sumOf { it.total_focus_ms }

            // 2. Past 7 Days Dates
            val past7Dates = (0..6).map { offset ->
                val cal = Calendar.getInstance()
                cal.timeInMillis = trueTime
                cal.add(Calendar.DAY_OF_YEAR, -offset)
                sdf.format(cal.time)
            }
            val past7Records = allLocalRecords.filter { it.date_string in past7Dates }
            val past7FocusMs = past7Records.sumOf { it.total_focus_ms }

            // 3. Past 30 Days Dates
            val past30Dates = (0..29).map { offset ->
                val cal = Calendar.getInstance()
                cal.timeInMillis = trueTime
                cal.add(Calendar.DAY_OF_YEAR, -offset)
                sdf.format(cal.time)
            }
            val past30Records = allLocalRecords.filter { it.date_string in past30Dates }
            val past30FocusMs = past30Records.sumOf { it.total_focus_ms }

            // 4. Past 50 Days Dates
            val past50Dates = (0..49).map { offset ->
                val cal = Calendar.getInstance()
                cal.timeInMillis = trueTime
                cal.add(Calendar.DAY_OF_YEAR, -offset)
                sdf.format(cal.time)
            }
            val past50Records = allLocalRecords.filter { it.date_string in past50Dates }
            val past50FocusMs = past50Records.sumOf { it.total_focus_ms }

            // 5. All Time
            val allTimeFocusMs = allLocalRecords.sumOf { it.total_focus_ms }

            // Retrieve adopted statistics to accumulate on top
            val adoptedAllTimeMs = appPrefs.getLong("adopted_all_time_ms_${sanitizedEmail}", 0L)
            val adoptedPast50Ms = appPrefs.getLong("adopted_past_50_ms_${sanitizedEmail}", 0L)
            val adoptedPast30Ms = appPrefs.getLong("adopted_past_30_ms_${sanitizedEmail}", 0L)
            val adoptedPast7Ms = appPrefs.getLong("adopted_past_7_ms_${sanitizedEmail}", 0L)
            val adoptedTodayMs = appPrefs.getLong("adopted_today_ms_${sanitizedEmail}", 0L)

            val finalTodayFocusMs = todayFocusMs + adoptedTodayMs
            val finalPast7FocusMs = past7FocusMs + adoptedPast7Ms
            val finalPast30FocusMs = past30FocusMs + adoptedPast30Ms
            val finalPast50FocusMs = past50FocusMs + adoptedPast50Ms
            val finalAllTimeFocusMs = allTimeFocusMs + adoptedAllTimeMs

            // Update respective static branches
            updateBranch(
                database, sanitizedEmail, "TODAYS_STAT", finalTodayFocusMs,
                getSubjectBreakdown(todayRecords), activeStreak, displayName, cachedEmoji, trueTime
            )
            updateBranch(
                database, sanitizedEmail, "PAST_7_DAYS_STAT", finalPast7FocusMs,
                getSubjectBreakdown(past7Records), activeStreak, displayName, cachedEmoji, trueTime
            )
            updateBranch(
                database, sanitizedEmail, "PAST_30_DAYS_STAT", finalPast30FocusMs,
                getSubjectBreakdown(past30Records), activeStreak, displayName, cachedEmoji, trueTime
            )
            updateBranch(
                database, sanitizedEmail, "PAST_50_DAYS_STAT", finalPast50FocusMs,
                getSubjectBreakdown(past50Records), activeStreak, displayName, cachedEmoji, trueTime
            )
            updateBranch(
                database, sanitizedEmail, "ALL_TIME_STAT", finalAllTimeFocusMs,
                getSubjectBreakdown(allLocalRecords), activeStreak, displayName, cachedEmoji, trueTime
            )

            Log.d(TAG, "Successfully recalculated and synchronized static stats branches to RTDB.")

            // Update device timings in Firebase
            DevicePresenceManager.updateDeviceFocusStats(context, email)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating weekly stats nodes in RTDB", e)
        }
    }

    private fun updateBranch(
        database: FirebaseDatabase,
        sanitizedEmail: String,
        branchName: String,
        totalFocusMs: Long,
        subjectBreakdown: Map<String, Long>,
        activeStreak: Int,
        displayName: String,
        cachedEmoji: String,
        trueTime: Long
    ) {
        val ref = database.getReference("FOCUS_TIMMER")
            .child("USER")
            .child(sanitizedEmail)
            .child("WEEKLY_STATS")
            .child(branchName)

        val xpScore = ArenaLeaderboardEngine.calculateXp(totalFocusMs, activeStreak)

        val updates = mapOf(
            "Total_Focus_Ms" to totalFocusMs,
            "Subject_Breakdown" to subjectBreakdown,
            "Last_Updated" to trueTime,
            "DisplayName" to displayName,
            "ActiveStreak" to activeStreak,
            "CustomEmoji" to cachedEmoji,
            "XpScore" to xpScore,
            "XP_Score" to xpScore,
            "xpScore" to xpScore
        )

        ref.setValue(updates)
    }

    private fun getSubjectBreakdown(records: List<com.example.data.LocalHistoryVault>): Map<String, Long> {
        val map = mutableMapOf<String, Long>()
        for (r in records) {
            val tag = r.subject.trim()
                .ifBlank { "Study" }
                .replace(".", "_")
                .replace("$", "_")
                .replace("[", "_")
                .replace("]", "_")
                .replace("#", "_")
                .replace("/", "_")
            map[tag] = (map[tag] ?: 0L) + r.total_focus_ms
        }
        return map
    }

    fun adoptCloudStatsOnLogin(context: Context, email: String) {
        if (email.isBlank()) return
        val dbUrl = FirebaseConfig.getDatabaseUrl(context)
        if (dbUrl.isEmpty()) return
        
        try {
            val database = FirebaseDatabase.getInstance(dbUrl)
            val sanitizedEmail = DevicePresenceManager.sanitizeEmail(email)
            val ref = database.getReference("FOCUS_TIMMER")
                .child("USER")
                .child(sanitizedEmail)
                .child("WEEKLY_STATS")

            ref.addListenerForSingleValueEvent(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    if (snapshot.exists()) {
                        val allTimeMs = snapshot.child("ALL_TIME_STAT").child("Total_Focus_Ms").getValue(Long::class.java) ?: 0L
                        val past50Ms = snapshot.child("PAST_50_DAYS_STAT").child("Total_Focus_Ms").getValue(Long::class.java) ?: 0L
                        val past30Ms = snapshot.child("PAST_30_DAYS_STAT").child("Total_Focus_Ms").getValue(Long::class.java) ?: 0L
                        val past7Ms = snapshot.child("PAST_7_DAYS_STAT").child("Total_Focus_Ms").getValue(Long::class.java) ?: 0L
                        val todayMs = snapshot.child("TODAYS_STAT").child("Total_Focus_Ms").getValue(Long::class.java) ?: 0L

                        Log.d(TAG, "adoptCloudStatsOnLogin found cloud stats: AllTime=$allTimeMs, Past50=$past50Ms, Past30=$past30Ms, Past7=$past7Ms, Today=$todayMs")

                        val db = com.example.data.AppDatabase.getInstance(context)
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            try {
                                val allHistory = db.localHistoryVaultDao().getAllHistoryDirect()
                                val nowMs = System.currentTimeMillis()
                                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                val todayStr = sdf.format(Date(nowMs))

                                var localTodayMs = 0L
                                var localPast7Ms = 0L
                                var localPast30Ms = 0L
                                var localPast50Ms = 0L
                                var localAllTimeMs = 0L

                                for (record in allHistory) {
                                    val duration = record.total_focus_ms
                                    localAllTimeMs += duration
                                    if (record.date_string == todayStr) {
                                        localTodayMs += duration
                                    }
                                    if (record.start_time_ms >= nowMs - (7L * 24 * 60 * 60 * 1000)) {
                                        localPast7Ms += duration
                                    }
                                    if (record.start_time_ms >= nowMs - (30L * 24 * 60 * 60 * 1000)) {
                                        localPast30Ms += duration
                                    }
                                    if (record.start_time_ms >= nowMs - (50L * 24 * 60 * 60 * 1000)) {
                                        localPast50Ms += duration
                                    }
                                }

                                val adoptedAllTimeMs = maxOf(0L, allTimeMs - localAllTimeMs)
                                val adoptedPast50Ms = maxOf(0L, past50Ms - localPast50Ms)
                                val adoptedPast30Ms = maxOf(0L, past30Ms - localPast30Ms)
                                val adoptedPast7Ms = maxOf(0L, past7Ms - localPast7Ms)
                                val adoptedTodayMs = maxOf(0L, todayMs - localTodayMs)

                                val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                val localTotalFocusMinutes = appPrefs.getInt("total_focus_minutes", 0)
                                val cloudTotalFocusMinutes = (allTimeMs / 1000 / 60).toInt()
                                val adoptedTotalFocusMinutes = maxOf(0, cloudTotalFocusMinutes - localTotalFocusMinutes)

                                appPrefs.edit().apply {
                                    putLong("adopted_all_time_ms_${sanitizedEmail}", adoptedAllTimeMs)
                                    putLong("adopted_past_50_ms_${sanitizedEmail}", adoptedPast50Ms)
                                    putLong("adopted_past_30_ms_${sanitizedEmail}", adoptedPast30Ms)
                                    putLong("adopted_past_7_ms_${sanitizedEmail}", adoptedPast7Ms)
                                    putLong("adopted_today_ms_${sanitizedEmail}", adoptedTodayMs)
                                    putInt("adopted_total_focus_minutes_${sanitizedEmail}", adoptedTotalFocusMinutes)

                                    val updatedTotalFocusMinutes = localTotalFocusMinutes + adoptedTotalFocusMinutes
                                    putInt("total_focus_minutes", updatedTotalFocusMinutes)
                                    apply()
                                }

                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    com.example.util.FocusTimerManager.setTotalFocusMinutes(localTotalFocusMinutes + adoptedTotalFocusMinutes)
                                }

                                Log.d(TAG, "Successfully adopted cloud stats on login: AllTime=$adoptedAllTimeMs ms, TotalFocusMinutes=$adoptedTotalFocusMinutes")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in adoptCloudStatsOnLogin background processing", e)
                            }
                        }
                    } else {
                        Log.d(TAG, "No existing cloud stats found for adoption.")
                    }
                }

                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                    Log.e(TAG, "adoptCloudStatsOnLogin cancelled: ${error.message}")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error in adoptCloudStatsOnLogin", e)
        }
    }
}
