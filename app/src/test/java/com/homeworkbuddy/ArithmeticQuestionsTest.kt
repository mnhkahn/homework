package com.homeworkbuddy

import org.junit.Assert.*
import org.junit.Test

class ArithmeticQuestionsTest {
    @Test fun textDescriptionPreservesAllTwentyQuestions() {
        val lines = (1..20).map { "$it + 17 = ___" }
        val details = HomeworkTaskDetails.fromDescription("类型: arithmetic\n预计用时: 5\n作业内容:\n" + lines.joinToString("\n"))
        assertEquals(HomeworkTaskType.ARITHMETIC, details.type)
        assertEquals(lines, arithmeticQuestions(details.task))
        assertNull(details.link)
    }

    @Test fun cliMetadataBeforeTaskSectionIsNotAQuestion() {
        val details = HomeworkTaskDetails.fromDescription("预计用时: 5\n当日第 2 项\n\n作业类型：口算\n作业内容：\n26 + 17 = ___\n54 − 28 = ___")
        assertEquals(HomeworkTaskType.ARITHMETIC, details.type)
        assertEquals(listOf("26 + 17 = ___", "54 − 28 = ___"), arithmeticQuestions(details.task))
    }

    @Test fun inlineFirstQuestionAndChineseTypeKeepFollowingLines() {
        val details = HomeworkTaskDetails.fromDescription("类型：口算\n作业内容：26 + 17 =\n54 - 28 =\n预计用时：5")
        assertEquals(listOf("26 + 17 =", "54 - 28 ="), arithmeticQuestions(details.task))
    }

    @Test fun jsonContentPreservesNewlinesAndIgnoresMetadata() {
        val details = HomeworkTaskDetails.fromDescription("""{"type":"arithmetic","task":"26 + 17 =\n54 - 28 =","link":"https://example.com"}""")
        assertEquals(listOf("26 + 17 =", "54 - 28 ="), arithmeticQuestions(details.task))
        assertNull(details.link)
    }

    @Test fun stripsListMarkersButPreservesDecimalsAndNegativeNumbers() {
        assertEquals(listOf("26 + 17 =", "54 − 28 =", "3 × 4 =", "1.5 + 2 =", "-3 + 4 ="),
            arithmeticQuestions("1. 26 + 17 =\n2)54 − 28 =；- 3 × 4 =\n1.5 + 2 =\n-3 + 4 ="))
    }

    @Test fun emptyAssignmentsStayEmpty() {
        assertTrue(arithmeticQuestions(HomeworkTaskDetails.fromDescription("类型: arithmetic\n作业内容:\n预计用时: 5").task).isEmpty())
        assertTrue(arithmeticQuestions(HomeworkTaskDetails.fromDescription("""{"type":"arithmetic"}""").task).isEmpty())
    }

    private val sizes = listOf(ArithmeticCellSize(26, 200, 32), ArithmeticCellSize(24, 180, 30), ArithmeticCellSize(22, 160, 28), ArithmeticCellSize(20, 145, 26))

    @Test fun wideShortCardUsesMoreColumnsToFitAllQuestions() {
        val plan = arithmeticGridPlan(20, 900, 160, 10, sizes)
        assertEquals(5, plan.columns)
        assertEquals(22, plan.fontSp)
        assertTrue(plan.fits)
    }

    @Test fun roomyCardKeepsLargerText() {
        val plan = arithmeticGridPlan(20, 600, 450, 10, sizes)
        assertEquals(26, plan.fontSp)
        assertTrue(plan.fits)
    }

    @Test fun narrowShortCardUsesReadableScrollableFallback() {
        val plan = arithmeticGridPlan(20, 130, 90, 10, sizes)
        assertEquals(1, plan.columns)
        assertEquals(20, plan.fontSp)
        assertFalse(plan.fits)
    }
}
