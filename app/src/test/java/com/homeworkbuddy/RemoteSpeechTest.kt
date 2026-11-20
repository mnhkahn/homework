package com.homeworkbuddy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteSpeechTest {
    @Test fun defaultsAndLimitsAreExplicit() {
        val value = RemoteSpeechRequest.parse(JSONObject().put("text", "  休息一下  "))
        assertEquals("休息一下", value.text)
        assertEquals("zh-CN", value.language)
        assertEquals(1f, value.rate)
        for (body in listOf(JSONObject(), JSONObject().put("text", "x".repeat(301)),
            JSONObject().put("text", "a").put("language", "invalid"),
            JSONObject().put("text", "a").put("rate", 3))) {
            assertThrows(McpException::class.java) { RemoteSpeechRequest.parse(body) }
        }
    }
}
