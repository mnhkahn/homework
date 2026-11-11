package com.homeworkbuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeworkRequirementTest {
    @Test fun requirementStopsAtTheNextCardFieldAndStaysOutOfTheQuestions() {
        val description = """
            作业类型：口算
            作业要求：
            写完检查一遍。
            拍照时要拍全。
            作业内容：
            10 + 2 =
            8 - 4 =
        """.trimIndent()

        assertEquals("写完检查一遍。\n拍照时要拍全。", HomeworkTaskDetails.requirements(description))
        assertEquals("10 + 2 =\n8 - 4 =", HomeworkTaskDetails.fromDescription(description).task)
    }

    @Test fun jsonRequirementsAreSeparateFromTheTaskContent() {
        val description = """{"type":"normal","task":"第12页","requirements":"写清题号。\n\n写完自己检查。"}"""

        assertEquals("写清题号。\n\n写完自己检查。", HomeworkTaskDetails.requirements(description))
        assertEquals("第12页", HomeworkTaskDetails.fromDescription(description).task)
        assertNull(HomeworkTaskDetails.fromDescription("""{"requirements":"写清题号。"}""").task)
        assertNull(HomeworkTaskDetails.requirements("预习要求：读课文。"))
    }

    @Test fun changingPreviewNotesKeepsTheGeneralRequirement() {
        val description = "作业类型：预习\n预习要求：读课文。\n\n作业要求：拍照时要拍全。"
        val edited = HomeworkTaskDetails.withPreviewRequirements(description, "读课文两遍。")

        assertEquals("读课文两遍。", HomeworkTaskDetails.fromDescription(edited).task)
        assertEquals("拍照时要拍全。", HomeworkTaskDetails.requirements(edited))
    }
}
