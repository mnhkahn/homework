package com.homeworkbuddy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TemporaryOpenRecord(val startedAt: Long, val plannedEnd: Long, val source: String, val endedAt: Long? = null)

/** Local audit of temporary openings; old counters do not contain timestamps. */
class TemporaryOpenHistory(context: Context) {
    private val prefs = context.getSharedPreferences("temporary_open_history", Context.MODE_PRIVATE)

    fun records(): List<TemporaryOpenRecord> = runCatching {
        val array = JSONArray(prefs.getString("records", "[]"))
        (0 until array.length()).map { index -> array.getJSONObject(index).let { value ->
            TemporaryOpenRecord(value.getLong("start"), value.getLong("planned_end"), value.getString("source"), value.optLong("ended_at").takeIf { it > 0 })
        } }
    }.getOrDefault(emptyList())

    fun start(now: Long, until: Long, source: String) {
        val existing = endActiveTemporaryOpen(records(), now)
        save((listOf(TemporaryOpenRecord(now, until, source)) + existing).take(100))
    }

    fun end(now: Long) = save(endActiveTemporaryOpen(records(), now))

    private fun save(records: List<TemporaryOpenRecord>) {
        val json = JSONArray()
        records.forEach { record ->
            json.put(JSONObject().put("start", record.startedAt).put("planned_end", record.plannedEnd)
                .put("source", record.source).put("ended_at", record.endedAt))
        }
        prefs.edit().putString("records", json.toString()).apply()
    }
}

internal fun endActiveTemporaryOpen(records: List<TemporaryOpenRecord>, now: Long): List<TemporaryOpenRecord> =
    records.map { if (it.endedAt == null && now >= it.startedAt && now < it.plannedEnd) it.copy(endedAt = now) else it }
