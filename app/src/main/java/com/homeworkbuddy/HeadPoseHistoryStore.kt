package com.homeworkbuddy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Local numeric history only; no camera frames or recordings. */
internal class HeadPoseHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("head_pose_history", Context.MODE_PRIVATE)
    private val cache = mutableMapOf<LocalDate, HeadPoseDayHistory>()

    fun dates(): List<LocalDate> = prefs.all.keys.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sortedDescending()

    fun day(date: LocalDate): HeadPoseDayHistory = decodeHeadPoseHistory(prefs.getString(date.toString(), null))

    fun observe(date: LocalDate, pitch: Float?, reminder: HeadPoseReminderRecord?): Boolean {
        val previous = cache.getOrPut(date) { day(date) }
        val updated = previous.observe(pitch, reminder)
        if (updated == previous) return false
        cache[date] = updated
        prefs.edit().putString(date.toString(), encodeHeadPoseHistory(updated)).apply()
        return true
    }
}

internal fun encodeHeadPoseHistory(day: HeadPoseDayHistory): String = JSONObject().apply {
    day.minimumPitch?.let { put("minimum_pitch", it.toDouble()) }
    put("reminders", JSONArray().apply {
        day.reminders.forEach { record ->
            put(JSONObject().put("id", record.id).put("reminded_at", record.remindedAtMillis)
                .put("minimum_pitch", record.minimumPitch.toDouble()).put("threshold", record.threshold.toDouble()))
        }
    })
}.toString()

internal fun decodeHeadPoseHistory(raw: String?): HeadPoseDayHistory = runCatching {
    if (raw == null) return HeadPoseDayHistory()
    val json = JSONObject(raw)
    val reminders = json.optJSONArray("reminders") ?: JSONArray()
    HeadPoseDayHistory(
        minimumPitch = json.optDouble("minimum_pitch", Double.NaN).toFloat().takeIf { it.isFinite() && it in -90f..90f },
        reminders = (0 until reminders.length()).mapNotNull { index ->
            runCatching {
                val record = reminders.getJSONObject(index)
                HeadPoseReminderRecord(record.getString("id"), record.getLong("reminded_at"),
                    record.getDouble("minimum_pitch").toFloat(), record.getDouble("threshold").toFloat())
                    .takeIf { it.minimumPitch.isFinite() && it.minimumPitch in -90f..90f && it.threshold.isFinite() }
            }.getOrNull()
        },
    )
}.getOrDefault(HeadPoseDayHistory())
