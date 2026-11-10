package com.homeworkbuddy

import android.content.Context

internal class PreviewRequirementsStore(context: Context) {
    private val prefs = context.getSharedPreferences("preview_requirements", Context.MODE_PRIVATE)

    fun read(): String? = prefs.getString("requirements", null)?.takeIf(String::isNotBlank)

    fun save(value: String) {
        prefs.edit().putString("requirements", value.trim().ifBlank { null }).apply()
    }
}
