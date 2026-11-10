package com.homeworkbuddy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class XiaoliNotificationTest {
    @Test fun readsServerPayloadAndAcknowledgesById() {
        val notification = checkNotNull(XiaoliNotification.parse(payload()))
        assertEquals("记得带上练习册", notification.content)
        assertEquals(Instant.parse("2026-11-10T09:00:00.123456789Z"), notification.publishedAt)
        val ack = notification.acknowledgement("active-session")
        assertEquals("notification_ack", ack.getString("type"))
        assertEquals("active-session", ack.getString("session_id"))
        assertEquals("ntf_current", ack.getString("notification_id"))
    }

    @Test fun replayCannotReplaceANewerNotification() {
        val latest = checkNotNull(XiaoliNotification.parse(payload()))
        val older = checkNotNull(XiaoliNotification.parse(payload()
            .put("id", "ntf_old")
            .put("published_at", "2026-11-10T09:00:00.123456788Z")))
        assertFalse(older.isNewerThan(latest))
        assertFalse(latest.isNewerThan(latest))
        assertTrue(latest.isNewerThan(older))
    }

    @Test fun checksExpiryAndRejectsUnusableContent() {
        val deadline = Instant.parse("2026-11-10T10:00:00Z")
        val notification = checkNotNull(XiaoliNotification.parse(payload().put("expires_at", deadline.toString())))
        assertTrue(notification.isActive(deadline.minusNanos(1)))
        assertFalse(notification.isActive(deadline))
        assertTrue(checkNotNull(XiaoliNotification.parse(payload())).isActive(deadline))
        assertNull(XiaoliNotification.parse(payload().put("content", " ")))
        assertNull(XiaoliNotification.parse(payload().put("expires_at", "invalid")))
    }

    private fun payload() = JSONObject()
        .put("id", "ntf_current")
        .put("title", "明日提醒")
        .put("content", "记得带上练习册")
        .put("published_at", "2026-11-10T17:00:00.123456789+08:00")
}
