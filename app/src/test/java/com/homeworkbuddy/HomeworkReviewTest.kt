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

    @Test fun publishedGradeAndCorrectionKeepReviewEntryWhenLookupFails() {
        val task = HomeworkTask("task", "英语", "听写", 10, java.time.LocalTime.NOON)
        assertFalse(task.canOpenReview(confirmedReview = false))
        assertTrue(task.canOpenReview(confirmedReview = true))
        assertTrue(task.copy(gradeResult = HomeworkGradeResult(HomeworkGrade.NEEDS_PRACTICE, true)).canOpenReview(false))
        assertTrue(task.copy(isCorrection = true).canOpenReview(false))
        assertTrue(task.copy(status = TaskStatus.NEEDS_CORRECTION).canOpenReview(false))
        assertTrue(task.copy(status = TaskStatus.COMPLETED).canOpenReview(false))
    }

    @Test fun readsCNoteAutomaticReviewWithProvisionalGradeAndReviewIdentity() {
        val payload = """{"version":1,"type":"text","review_id":"cnote-review-fixture","grade":"差","provisional":true,"has_errors":true,"text":"正确 10 · 需订正 8 · 待核对 1 / 共 19 项\n第1项：识别为「e-reder」，需订正。"}"""
        val review = parse(payload)!!
        assertEquals(HomeworkGradeResult(HomeworkGrade.NEEDS_PRACTICE, true), review.gradeResult)
        assertEquals(true, review.hasErrors)
        assertTrue(review.text!!.contains("e-reder"))
        assertFalse(review.text.contains("review_id"))
    }

    @Test fun legacyAutomaticFeedbackOmitsRepeatedGradeAndCorrectRows() {
        val review = HomeworkReview("action", "date", "text",
            text = "打分结果：暂评·差\n正确 1 · 需订正 1 / 共 2 项\n第1题：正确。正确\n第2题：需订正。请重新听写。",
            gradeResult = HomeworkGradeResult(HomeworkGrade.NEEDS_PRACTICE, true))
        assertEquals("正确 1 · 需订正 1 / 共 2 项\n第2题：需订正。请重新听写。", review.displayText)
        assertTrue(review.text!!.contains("第1题")) // The original saved feedback stays intact.
    }

    @Test fun wordCorrectionPairsRemainOnePerLineInClientDisplay() {
        val text = "需订正：\ntemud: lemonade\n待核对：\ne-reder game console: hungry"
        val payload = org.json.JSONObject().put("version", 1).put("type", "text").put("grade", "差")
            .put("provisional", true).put("has_errors", true).put("text", text)
        val review = parse(payload.toString())!!
        assertEquals(text, review.displayText)
        assertEquals(HomeworkGradeResult(HomeworkGrade.NEEDS_PRACTICE, true), review.gradeResult)
    }

}
