package com.homeworkbuddy

import org.junit.Assert.*
import org.junit.Test

class HomeworkGradeTest {
    @Test fun gradesAndProvisionalGradesRoundTrip() {
        HomeworkGrade.entries.forEach { grade ->
            (if (grade == HomeworkGrade.PERFECT) listOf(false) else listOf(false, true)).forEach { provisional ->
                val result = HomeworkGradeResult(grade, provisional)
                assertEquals(result, HomeworkGradeResult.parse(result.fieldValue))
            }
        }
        listOf(null, "", "已完成", "99", "null").forEach { assertNull(HomeworkGradeResult.parse(it)) }
        assertEquals(HomeworkGradeResult(HomeworkGrade.PERFECT), HomeworkGradeResult.fromDescription("打分结果：100\n作业内容：123"))
        assertNull(HomeworkGradeResult.parse("暂评·100"))
    }

    @Test fun perfectGradeHidesCorrectionOnlyForFinalFullMarks() {
        val task = HomeworkTask("id", "数学", "口算", 10, java.time.LocalTime.NOON, isCorrection = true)
        assertTrue(task.canCorrect)
        assertFalse(task.copy(gradeResult = HomeworkGradeResult(HomeworkGrade.PERFECT)).canCorrect)
        assertTrue(task.copy(gradeResult = HomeworkGradeResult(HomeworkGrade.PERFECT, provisional = true)).canCorrect)
    }

    @Test fun scoreHeaderIsReadWithoutInterpretingQuestionText() {
        assertEquals(HomeworkGradeResult(HomeworkGrade.EXCELLENT, true), HomeworkGradeResult.fromDescription("打分结果：暂评·优\n作业内容：123"))
        assertEquals(HomeworkGradeResult(HomeworkGrade.GOOD), HomeworkGradeResult.fromDescription("打分结果: 良\n作业内容：123"))
        assertNull(HomeworkGradeResult.fromDescription("作业内容：\n打分结果：差"))
        assertNull(HomeworkGradeResult.fromDescription("打分结果：待批改\n作业内容：123"))
        assertNull(HomeworkGradeResult.fromDescription(""))
    }

    @Test fun reviewHistoryKeepsItsOwnGradeAndLegacyReviewsStillWork() {
        val old = "作业批改：{\"version\":1,\"type\":\"text\",\"text\":\"请检查第3题\"}"
        assertNotNull(HomeworkReview.parse("old", "date", old))
        assertNull(HomeworkReview.parse("old", "date", old)?.gradeResult)
        val graded = "作业批改：{\"version\":1,\"type\":\"text\",\"text\":\"请检查第3题\",\"grade\":\"良\",\"provisional\":true}"
        assertEquals(HomeworkGradeResult(HomeworkGrade.GOOD, true), HomeworkReview.parse("new", "date", graded)?.gradeResult)
        val perfect = "作业批改：{\"version\":1,\"type\":\"text\",\"text\":\"全部正确\",\"grade\":\"100\",\"has_errors\":false}"
        assertEquals(HomeworkGradeResult(HomeworkGrade.PERFECT), HomeworkReview.parse("perfect", "date", perfect)?.gradeResult)
    }
}
