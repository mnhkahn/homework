package com.homeworkbuddy

import org.junit.Assert.*
import org.junit.Test

class TrelloBindingTest {
    private val saved = mapOf("待完成" to "todo", "已完成" to "done", "待改错" to "fix", "已改错" to "fixed")
    private val lists = listOf(TrelloOption("todo", "待完成"), TrelloOption("done", "已完成"), TrelloOption("fix", "待改错"), TrelloOption("fixed", "已改错"))

    @Test fun acceptsOnlyMatchingAccessibleListIdsAndNames() {
        assertTrue(savedBindingMatches(saved, lists))
        assertFalse(savedBindingMatches(saved, lists.filterNot { it.id == "done" }))
        assertFalse(savedBindingMatches(saved, lists.map { if (it.id == "done") it.copy(name = "其他") else it }))
        assertFalse(savedBindingMatches(saved, lists.map { if (it.id == "todo") it.copy(id = "other") else it }))
    }

    @Test fun olderBindingsMayOmitCorrectionLists() {
        assertTrue(savedBindingMatches(saved + mapOf("待改错" to null, "已改错" to null), lists.take(2)))
    }
}
