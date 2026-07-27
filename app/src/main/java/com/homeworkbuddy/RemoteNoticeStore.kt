package com.homeworkbuddy

import android.content.Context
import java.time.Instant
import java.time.ZoneId

/** The most recent message is kept locally so it is visible on the child home screen. */
data class RemoteNotice(val title: String, val body: String, val receivedAt: Long)

class RemoteNoticeStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(): RemoteNotice? {
        val title = prefs.getString(TITLE, null) ?: return null
        val body = prefs.getString(BODY, null) ?: return null
        val receivedAt = prefs.getLong(RECEIVED_AT, 0L)
        val receivedDay = Instant.ofEpochMilli(receivedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        if (receivedAt <= 0L || receivedDay != java.time.LocalDate.now()) {
            prefs.edit().clear().apply()
            return null
        }
        return RemoteNotice(title, body, receivedAt)
    }

    fun save(title: String, body: String) {
        prefs.edit().putString(TITLE, title).putString(BODY, body)
            .putLong(RECEIVED_AT, System.currentTimeMillis()).apply()
    }

    fun addChangeListener(onChanged: () -> Unit) =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChanged() }
            .also { prefs.registerOnSharedPreferenceChangeListener(it) }

    fun removeChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private companion object {
        const val PREFS = "remote_notice"
        const val TITLE = "title"
        const val BODY = "body"
        const val RECEIVED_AT = "received_at"
    }
}
