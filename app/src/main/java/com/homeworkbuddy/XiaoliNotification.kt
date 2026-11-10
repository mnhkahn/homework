package com.homeworkbuddy

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

internal data class XiaoliNotification(
    val id: String,
    val content: String,
    val publishedAt: Instant,
    val expiresAt: Instant?,
) {
    fun isActive(now: Instant) = expiresAt == null || now.isBefore(expiresAt)

    fun isNewerThan(other: XiaoliNotification): Boolean =
        compareValuesBy(this, other, XiaoliNotification::publishedAt, XiaoliNotification::id) > 0

    fun acknowledgement(sessionId: String): JSONObject = JSONObject()
        .put("type", "notification_ack")
        .put("session_id", sessionId)
        .put("notification_id", id)

    companion object {
        fun parse(payload: JSONObject): XiaoliNotification? {
            val id = payload.opt("id") as? String ?: return null
            val content = payload.opt("content") as? String ?: return null
            if (id.isBlank() || content.isBlank()) return null
            return runCatching {
                val publishedAt = OffsetDateTime.parse(payload.getString("published_at")).toInstant()
                val expiresAt = if (payload.isNull("expires_at")) null
                    else OffsetDateTime.parse(payload.getString("expires_at")).toInstant()
                XiaoliNotification(id, content, publishedAt, expiresAt)
            }.getOrNull()
        }
    }
}
