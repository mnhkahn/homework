package com.homeworkbuddy

import org.junit.Assert.*
import org.junit.Test

class ParentTotpTest {
    private val secret = ParentTotp.encode("12345678901234567890".toByteArray())

    @Test fun rfc6238Sha1VectorsAsSixDigitCodes() {
        assertEquals("287082", ParentTotp.code(secret, 59L / 30))
        assertEquals("081804", ParentTotp.code(secret, 1111111109L / 30))
        assertEquals("005924", ParentTotp.code(secret, 1234567890L / 30))
        assertArrayEquals("12345678901234567890".toByteArray(), ParentTotp.decode(secret))
    }

    @Test fun toleratesOneTimeStepButRejectsReplayedAndInvalidCodes() {
        val now = 1_700_000_010L
        val step = now / 30
        val code = ParentTotp.code(secret, step)
        assertEquals(step, ParentTotp.matchingStep(secret, code, now))
        assertNull(ParentTotp.matchingStep(secret, code, now, step))
        assertEquals(step - 1, ParentTotp.matchingStep(secret, ParentTotp.code(secret, step - 1), now))
        assertNull(ParentTotp.matchingStep(secret, "abc123", now))
    }
}
