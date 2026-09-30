package com.homeworkbuddy

import android.content.Context

internal class HeadPoseSettings(context: Context) {
    val prefs = context.getSharedPreferences("head_pose", Context.MODE_PRIVATE)
    fun load(): HeadPoseLimits = with(prefs) {
        val d = HeadPoseLimits()
        HeadPoseLimits(getFloat("pitch_min", d.pitchMin), getFloat("pitch_max", d.pitchMax),
            getFloat("yaw_min", d.yawMin), getFloat("yaw_max", d.yawMax),
            getFloat("distance_min", d.distanceMin), getFloat("distance_max", d.distanceMax),
            getInt("trigger", d.triggerSeconds), getInt("recovery", d.recoverySeconds), getBoolean("sound", true))
            .takeIf { it.valid() } ?: d
    }
    fun save(value: HeadPoseLimits) {
        require(value.valid())
        prefs.edit().putFloat("pitch_min", value.pitchMin).putFloat("pitch_max", value.pitchMax)
            .putFloat("yaw_min", value.yawMin).putFloat("yaw_max", value.yawMax)
            .putFloat("distance_min", value.distanceMin).putFloat("distance_max", value.distanceMax)
            .putInt("trigger", value.triggerSeconds).putInt("recovery", value.recoverySeconds)
            .putBoolean("sound", value.sound).apply()
    }
    fun baseline(key: String): Float? = prefs.getFloat("baseline_$key", 0f).takeIf { it.isFinite() && it > 0f }
    fun saveBaseline(key: String, span: Float) { prefs.edit().putFloat("baseline_$key", span).apply() }
    fun resetBaseline() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("baseline_") }.forEach(edit::remove)
        edit.apply()
    }
}
