package com.homeworkbuddy

import android.content.Context
import java.time.LocalDate

data class PianoPracticeStatus(val count: Int, val cooldownSeconds: Int)

/** A local, per-card daily practice counter.  It deliberately never guesses practice time. */
class PianoPracticeStore(context: Context) {
    private val prefs = context.getSharedPreferences("piano_practice", Context.MODE_PRIVATE)

    fun status(taskId: String): PianoPracticeStatus {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(lastKey(taskId), 0L)
        val remaining = ((COOLDOWN_MS - (now - last)).coerceAtLeast(0L) + 999L) / 1_000L
        return PianoPracticeStatus(prefs.getInt(countKey(taskId), 0), remaining.toInt())
    }

    fun record(taskId: String): PianoPracticeStatus {
        val current = status(taskId)
        if (current.cooldownSeconds > 0) return current
        val nextCount = current.count + 1
        val todayTotal = prefs.getInt(totalKey(), 0) + 1
        prefs.edit()
            .putInt(countKey(taskId), nextCount)
            .putLong(lastKey(taskId), System.currentTimeMillis())
            .putInt(totalKey(), todayTotal)
            .apply()
        return PianoPracticeStatus(nextCount, COOLDOWN_SECONDS)
    }

    fun todayTotal(): Int = prefs.getInt(totalKey(), 0)

    private fun prefix() = LocalDate.now().toString()
    private fun countKey(taskId: String) = "${prefix()}:count:$taskId"
    private fun lastKey(taskId: String) = "${prefix()}:last:$taskId"
    private fun totalKey() = "${prefix()}:total"

    private companion object {
        const val COOLDOWN_SECONDS = 15
        const val COOLDOWN_MS = COOLDOWN_SECONDS * 1_000L
    }
}
