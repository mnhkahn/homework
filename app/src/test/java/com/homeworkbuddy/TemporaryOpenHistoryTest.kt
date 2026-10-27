package com.homeworkbuddy

import org.junit.Assert.assertEquals
import org.junit.Test

class TemporaryOpenHistoryTest {
    @Test fun endingAnOverrideRecordsItsActualEndWithoutChangingOldRecords() {
        val active = TemporaryOpenRecord(1000, 5000, "家长设置")
        val expired = TemporaryOpenRecord(100, 900, "小李远程开放")
        val alreadyEnded = TemporaryOpenRecord(10, 8000, "Trello 授权", 700)
        val result = endActiveTemporaryOpen(listOf(active, expired, alreadyEnded), 3000)
        assertEquals(active.copy(endedAt = 3000), result[0])
        assertEquals(expired, result[1])
        assertEquals(alreadyEnded, result[2])
    }
}
