package com.homeworkbuddy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMicrophonePolicyTest {
    @Test fun allowsVisibleReadingPage() {
        assertTrue(WebMicrophonePolicy.allows("https://www.cyeam.com/", "https://www.cyeam.com/ai/translate?words=hello"))
        assertTrue(WebMicrophonePolicy.allows("https://www.cyeam.com:443/", "https://www.cyeam.com/ai/translate"))
    }

    @Test fun rejectsOtherOriginsAndInsecurePages() {
        val page = "https://www.cyeam.com/ai/translate"
        listOf("http://www.cyeam.com", "https://evilcyeam.com", "https://www.cyeam.com.evil.test",
            "https://cyeam.com", "https://www.cyeam.com:444", "https://user@www.cyeam.com", "invalid"
        ).forEach { assertFalse(it, WebMicrophonePolicy.allows(it, page)) }
        assertFalse(WebMicrophonePolicy.allows("https://www.cyeam.com", "http://www.cyeam.com/ai/translate"))
        assertFalse(WebMicrophonePolicy.allows("https://www.cyeam.com", null))
    }
}
