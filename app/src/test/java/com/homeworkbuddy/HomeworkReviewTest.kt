package com.homeworkbuddy

import org.junit.Assert.*
import org.junit.Test

class HomeworkReviewTest {
    private fun parse(json: String) = HomeworkReview.parse("action-1", "2026-10-24T10:00:00Z", "作业批改：\n$json")

    @Test fun acceptsExactlyTheThreePayloadTypes() {
        assertEquals("第三题需要订正。", parse("""{"version":1,"type":"text","text":"第三题需要订正。"}""")?.text)
        assertEquals("attachment-1", parse("""{"version":1,"type":"image","attachment_id":"attachment-1"}""")?.attachmentId)
        assertEquals("audio", parse("""{"version":1,"type":"audio","attachment_id":"attachment-2"}""")?.type)
    }

    @Test fun rejectsUnmarkedStudentAnswersAndOrdinaryComments() {
        assertNull(HomeworkReview.parse("id", "date", "作业文字提交：\n答案是 42"))
        assertNull(HomeworkReview.parse("id", "date", "第三题需要订正"))
        assertNull(HomeworkReview.parse("id", "date", """{"version":1,"type":"text","text":"评语"}"""))
    }

    @Test fun rejectsMixedEmptyUnknownAndMalformedPayloads() {
        listOf(
            """{"version":1,"type":"text","text":" "}""",
            """{"version":1,"type":"text","text":"评语","attachment_id":"file"}""",
            """{"version":1,"type":"audio","text":"评语","attachment_id":"file"}""",
            """{"version":1,"type":"image"}""",
            """{"version":1,"type":"video","attachment_id":"file"}""",
            """{"version":2,"type":"text","text":"评语"}""",
            """{"version":1.5,"type":"text","text":"评语"}""",
            """{"version":1,"type":"text","text":123}""",
            """{"version":1,"type":"text","text":"评语","attachment_id":null}""",
            "not json",
        ).forEach { assertNull(it, parse(it)) }
    }

    @Test fun keepsTrelloActionIdentityAndMultilineText() {
        val review = parse("""{"version":1,"type":"text","text":"第一题正确。\n第二题：请检查进位。"}""")!!
        assertEquals("action-1", review.id)
        assertEquals("2026-10-24T10:00:00Z", review.date)
        assertEquals("第一题正确。\n第二题：请检查进位。", review.text)
    }

    @Test fun recognizesMediaWhenTrelloOmitsMimeTypes() {
        assertTrue(HomeworkAttachment("url", "批改.PNG", "").isReviewImage)
        assertTrue(HomeworkAttachment("url", "批改.opus", "application/octet-stream").isReviewAudio)
        assertFalse(HomeworkAttachment("url", "练习.pdf", "application/pdf").isReviewImage)
        assertFalse(HomeworkAttachment("url", "原稿.jpg", "image/jpeg").isReviewAudio)
    }
    @Test fun errorFlagIsExplicitAndIndependentOfMediaType() {
        assertEquals(true, parse("""{"version":1,"type":"text","text":"请订正","has_errors":true}""")?.hasErrors)
        assertEquals(false, parse("""{"version":1,"type":"image","attachment_id":"file","has_errors":false}""")?.hasErrors)
        assertNull(parse("""{"version":1,"type":"text","text":"评语"}""")?.hasErrors)
        assertNull(parse("""{"version":1,"type":"text","text":"评语","has_errors":"true"}"""))
    }

}
