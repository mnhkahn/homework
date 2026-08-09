package com.homeworkbuddy

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val CAPTURE_STATUS_TTL_MS = 15 * 60 * 1_000L

enum class CaptureKind { PHOTO, VIDEO, STREAM }

data class CaptureStatus(val kind: CaptureKind, val active: Boolean, val atMillis: Long) {
    fun label(): String {
        if (active) return when (kind) {
            CaptureKind.PHOTO -> "正在拍照"
            CaptureKind.VIDEO, CaptureKind.STREAM -> "录制中"
        }
        val time = Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))
        return when (kind) {
            CaptureKind.PHOTO -> "$time 已拍照"
            CaptureKind.VIDEO, CaptureKind.STREAM -> "$time 已录制"
        }
    }
}

/** A temporary, observable home-screen status for visible remote camera actions. */
class CaptureStatusStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(now: Long = System.currentTimeMillis()): CaptureStatus? {
        val at = prefs.getLong(AT, 0L)
        if (at <= 0L || now - at >= CAPTURE_STATUS_TTL_MS) {
            if (at > 0L) prefs.edit().clear().apply()
            return null
        }
        val kind = prefs.getString(KIND, null)?.let { runCatching { CaptureKind.valueOf(it) }.getOrNull() } ?: return null
        return CaptureStatus(kind, prefs.getBoolean(ACTIVE, false), at)
    }

    fun begin(kind: CaptureKind) = save(kind, active = true)
    fun complete(kind: CaptureKind) = save(kind, active = false)
    fun clear() = prefs.edit().clear().apply()

    /** An active capture cannot survive an app process restart. */
    fun clearInterruptedCapture() {
        if (prefs.getBoolean(ACTIVE, false)) clear()
    }

    fun addChangeListener(onChanged: () -> Unit) =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChanged() }
            .also { prefs.registerOnSharedPreferenceChangeListener(it) }

    fun removeChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun save(kind: CaptureKind, active: Boolean) {
        prefs.edit().putString(KIND, kind.name).putBoolean(ACTIVE, active)
            .putLong(AT, System.currentTimeMillis()).apply()
    }

    private companion object {
        const val PREFS = "capture_status"
        const val KIND = "kind"
        const val ACTIVE = "active"
        const val AT = "at"
    }
}
