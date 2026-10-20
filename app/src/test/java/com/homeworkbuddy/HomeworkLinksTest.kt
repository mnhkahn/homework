package com.homeworkbuddy

import org.junit.Assert.*
import org.junit.Test

class HomeworkLinksTest {
    @Test fun plainLinksAndTaskContentBecomeNormalHomeworkLinks() {
        listOf("http://example.com/lesson?q=1#page", "https://example.com/lesson").forEach { url ->
            listOf(url, "类型: 普通作业\n作业内容: $url\n预计用时: 15").forEach { description ->
                val details = HomeworkTaskDetails.fromDescription(description)
                assertEquals(HomeworkTaskType.NORMAL, details.type)
                assertEquals(url, details.task)
                assertEquals(url, details.link)
            }
        }
    }

    @Test fun separateLinkSupportsTextAndJsonAndPreservesInstructions() {
        listOf("链接", "作业链接", "link").forEach { label ->
            val details = HomeworkTaskDetails.fromDescription("类型: normal\n作业内容: 完成第一课\n$label: https://example.com/lesson\n预计用时: 15")
            assertEquals("完成第一课", details.task)
            assertEquals("https://example.com/lesson", details.link)
        }
        val details = HomeworkTaskDetails.fromDescription("""{"type":"normal","task":"完成第一课","link":"http://example.com/lesson"}""")
        assertEquals("完成第一课", details.task)
        assertEquals("http://example.com/lesson", details.link)
        assertEquals("http://example.com", HomeworkTaskDetails.fromDescription("链接: http://example.com").link)
    }

    @Test fun invalidOrNonWebDestinationsNeverBecomeButtons() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "intent://example.com", "https://", "https:///lesson", "https://user:pass@example.com/", "https://example.com:99999", "https://example.com/a b").forEach { url ->
            assertFalse(url, HomeworkLinks.isWebUrl(url))
            assertNull(HomeworkTaskDetails.fromDescription("类型: normal\n链接: $url").link)
        }
    }

    @Test fun initialAssignmentMayBeOutsideAllowlistButLaterNavigationIsBlocked() {
        val assignment = "http://example.com/lesson"
        assertTrue(HomeworkLinks.isWebUrl(assignment))
        listOf(assignment, "https://other.com/login", "https://example.com/next").forEach {
            assertFalse(it, HomeworkLinks.allowsNavigation(it))
        }
    }

    @Test fun laterNavigationAllowsStudyHostsAndTheirSubdomainsOnly() {
        listOf("https://cyeam.com", "https://www.cyeam.com/lesson", "http://trello.com/card", "https://download.pgyer.com/file", "https://WWW.CYEAM.COM/next").forEach {
            assertTrue(it, HomeworkLinks.allowsNavigation(it))
        }
        listOf("https://evilcyeam.com", "https://cyeam.com.evil.com", "https://cyeam.com@evil.com", "javascript:alert(1)", "file:///lesson", "intent://cyeam.com").forEach {
            assertFalse(it, HomeworkLinks.allowsNavigation(it))
        }
    }
}
