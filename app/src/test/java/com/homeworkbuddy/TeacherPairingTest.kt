package com.homeworkbuddy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TeacherPairingTest {
    @Test fun acceptsLanQrWithoutAnyPresetDeviceOrServer() {
        val qr = TeacherPairing.request("""{"pair_url":"http://192.168.1.12:8787/xiaozhi/pair","code":"one-time-code"}""")
        assertEquals("http://192.168.1.12:8787/xiaozhi/pair", qr.url)
        assertEquals("one-time-code", qr.code)
        assertEquals("https://teacher.example/xiaozhi/pair", TeacherPairing.request("""{"pair_url":"https://teacher.example/xiaozhi/pair","code":"new-code"}""").url)
    }

    @Test fun readsMrLiResponseAndReplacesOldConnectionIncludingItsToken() {
        val old = TeacherPairing.response(response("wss://old.example/xiaozhi/v1/", "old-token"), "tablet-local", "学习平板")
        val current = TeacherPairing.response(response("ws://192.168.1.12:8787/xiaozhi/v1/", "new-token")
            .put("device", JSONObject().put("id", "paired-tablet").put("name", "书桌平板")), "tablet-local", "学习平板")
        assertEquals("paired-tablet", current.deviceId)
        assertEquals("书桌平板", current.deviceName)
        assertEquals("ws://192.168.1.12:8787/xiaozhi/v1/", current.websocketUrl)
        assertEquals("new-token", current.token)
        assertNotEquals(old.token, current.token)
        assertNull(current.snapshotUrl)
    }

    @Test fun preservesLegacyOptionalUploadFieldsAndNewCredentialsOnSameServer() {
        val payload = response("wss://teacher.example/xiaozhi/v1/", "replacement-token")
            .put("device_id", "server-device")
            .put("vision_snapshot_url", "https://teacher.example/mcp/vision/snapshot")
        val config = TeacherPairing.response(payload, "local-device", "学习平板")
        assertEquals("server-device", config.deviceId)
        assertEquals("replacement-token", config.token)
        assertEquals("https://teacher.example/mcp/vision/snapshot", config.snapshotUrl)
    }

    @Test fun rejectsMissingCredentialsOrInvalidPairingAddresses() {
        for (url in listOf("file:///tmp/pair", "http:///xiaozhi/pair", "https://user:secret@teacher.example/pair")) {
            assertThrows(IllegalArgumentException::class.java) { TeacherPairing.request(JSONObject().put("pair_url", url).put("code", "code").toString()) }
        }
        assertThrows(IllegalArgumentException::class.java) { TeacherPairing.request("""{"pair_url":"https://teacher.example/pair","code":" "}""") }
        assertThrows(IllegalArgumentException::class.java) { TeacherPairing.response(response("https://teacher.example", "token"), "id", "name") }
        assertThrows(IllegalArgumentException::class.java) { TeacherPairing.response(response("wss://teacher.example/ws", " "), "id", "name") }
    }

    @Test fun declaresOnlyToolsMigratedToTeacherGateway() {
        val definitions = McpTools.definitions()
        val names = (0 until definitions.length()).map { definitions.getJSONObject(it).getString("name") }.toSet()
        assertEquals(setOf("self.device.get_status", "self.homework.get_status", "self.homework.get_weekly_report", "self.notify.send", "self.camera.take_photo", "self.camera.snapshot", "self.kiosk.pause_15_minutes"), names)
    }

    private fun response(url: String, token: String) = JSONObject().put("websocket", JSONObject().put("url", url).put("token", token))
}
