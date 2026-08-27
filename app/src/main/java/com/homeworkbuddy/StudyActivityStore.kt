package com.homeworkbuddy

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/** Study-time app behavior counters for the home-screen header. */
data class StudyActivity(val switches: Int, val blockedSeconds: Long)

/**
 * Per-day counters written by StudySessionService: how often the child switched
 * between apps during study time, and how long non-allowlisted apps stayed in
 * the foreground. The home screen hides the badge entirely while both are zero.
 */
class StudyActivityStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun today(): StudyActivity = StudyActivity(
        prefs.getInt(switchKey(LocalDate.now()), 0),
        prefs.getLong(blockedKey(LocalDate.now()), 0L),
    )

    fun noteSwitch() {
        val key = switchKey(LocalDate.now())
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
        prune()
    }

    fun addBlockedSeconds(seconds: Long) {
        val key = blockedKey(LocalDate.now())
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + seconds).apply()
        prune()
    }

    fun addChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /** Yesterday's counters are worthless to the home screen; drop them. */
    private fun prune() {
        val today = LocalDate.now().toString()
        val stale = prefs.all.keys.filter {
            (it.startsWith(SWITCH_PREFIX) || it.startsWith(BLOCKED_PREFIX)) && !it.endsWith(today)
        }
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    private fun switchKey(date: LocalDate) = "$SWITCH_PREFIX$date"
    private fun blockedKey(date: LocalDate) = "$BLOCKED_PREFIX$date"

    companion object {
        private const val PREFS = "study_activity"
        private const val SWITCH_PREFIX = "switches:"
        private const val BLOCKED_PREFIX = "blocked_seconds:"
    }
}
