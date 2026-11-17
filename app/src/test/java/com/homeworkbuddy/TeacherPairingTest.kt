package com.homeworkbuddy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TeacherPairingTest {
    @Test fun acceptsOnlyMrLiQrCodesOnTheNewApi() {
        val qr = TeacherPairing.request(qr("http://192.168.1.12:8787/tablet/v1/pair").toString())
        assertEquals("http://192.168.1.12:8787/tablet/v1/pair", qr.url)
        assertEquals("one-time-code", qr.code)
        assertEquals("https://teacher.example/tablet/v1/pair", TeacherPairing.request(qr("https://teacher.example/tablet/v1/pair").toString()).url)
    }

    @Test fun rejectsOldServerQrEvenWhenItHasAUsableUrlAndCode() {
        val legacy = JSONObject().put("pair_url", "https://xiaoli-server.example/xiaozhi/pair").put("code", "valid-looking-code")
        assertThrows(IllegalArgumentException::class.java) { TeacherPairing.request(legacy.toString()) }
        assertThrows(IllegalArgumentException::class.java) { TeacherPairing.request(legacy.put("service", "mr-li").put("protocol", 1).toString()) }
        assertThrows(IllegalArgumentException::class.java) { TeacherPairing.request(qr("https://teacher.example/tablet/v1/pair").put("protocol", 2).toString()) }
    }

    @Test fun requiresNewServiceIdentityAndCredentialShape() {
        val current = TeacherPairing.response(response())
        assertEquals("paired-tablet", current.deviceId)
        assertEquals("书桌平板", current.deviceName)
        assertEquals("ws://192.168.1.12:8787/tablet/v1/ws", current.websocketUrl)
        assertEquals("new-token", current.token)
        val legacy = JSONObject().put("device", JSONObject().put("id", "old-device"))
            .put("websocket", JSONObject().put("url", "wss://xiaoli-server.example/xiaozhi/v1/").put("token", "old-token"))
        assertThrows(IllegalArgumentException::class.java) { TeacherPairing.response(legacy) }
        assertThrows(IllegalArgumentException::class.java) { TeacherPairing.response(response().put("service", "xiaoli")) }
    }

    @Test fun onlyNewGatewayHelloCanLightUpTheCloud() {
        assertFalse(TeacherPairing.isGateway(JSONObject().put("type", "hello").put("version", 1).put("session_id", "old-session")))
        assertTrue(TeacherPairing.isGateway(JSONObject().put("type", "hello").put("service", "mr-li").put("protocol", 1).put("session_id", "new-session")))
        assertFalse(TeacherPairing.isConnectionUrl("wss://xiaoli-server.example/xiaozhi/v1/"))
    }

    @Test fun rejectsMissingCredentialsAndInvalidAddresses() {
        for (url in listOf("file:///tmp/pair", "http:///tablet/v1/pair", "https://user:secret@teacher.example/tablet/v1/pair")) {
            assertThrows(IllegalArgumentException::class.java) { TeacherPairing.request(qr(url).toString()) }
        }
        assertThrows(IllegalArgumentException::class.java) { TeacherPairing.request(qr("https://teacher.example/tablet/v1/pair").put("code", " ").toString()) }
        assertThrows(IllegalArgumentException::class.java) { TeacherPairing.response(response().put("connection", JSONObject().put("url", "wss://teacher.example/tablet/v1/ws").put("token", " "))) }
    }

    @Test fun declaresOnlyToolsMigratedToTeacherGateway() {
        val definitions = McpTools.definitions()
        val names = (0 until definitions.length()).map { definitions.getJSONObject(it).getString("name") }.toSet()
        assertEquals(setOf("self.device.get_status", "self.homework.get_status", "self.homework.get_weekly_report", "self.notify.send", "self.camera.take_photo", "self.camera.snapshot", "self.kiosk.pause_15_minutes"), names)
    }

    private fun qr(url: String) = JSONObject().put("service", "mr-li").put("protocol", 1).put("pair_url", url).put("code", "one-time-code")
    private fun response() = JSONObject().put("service", "mr-li").put("protocol", 1)
        .put("device", JSONObject().put("id", "paired-tablet").put("name", "书桌平板"))
        .put("connection", JSONObject().put("url", "ws://192.168.1.12:8787/tablet/v1/ws").put("token", "new-token"))
}
