package com.homeworkbuddy

import android.app.Application
import android.app.NotificationManager
import android.content.Context

class TeacherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        removeLegacyGatewayBinding(this)
    }
}

/** Destructive cleanup only: old credentials are never imported or decrypted. */
internal fun removeLegacyGatewayBinding(context: Context) {
    val upgrade = context.getSharedPreferences("teacher_gateway_upgrade", Context.MODE_PRIVATE)
    if (upgrade.getBoolean("legacy_removed", false)) return
    context.getSharedPreferences("xiaoli_device", Context.MODE_PRIVATE).edit().clear().commit()
    context.deleteSharedPreferences("xiaoli_device")
    context.getSystemService(NotificationManager::class.java).deleteNotificationChannel("xiaoli_requests")
    // The old Keystore alias is also used by Trello and parent TOTP. It is not
    // read by the new gateway; deleting it would destroy unrelated credentials.
    upgrade.edit().putBoolean("legacy_removed", true).commit()
}
