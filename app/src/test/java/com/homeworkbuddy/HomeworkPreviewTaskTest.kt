package com.homeworkbuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeworkPreviewTaskTest {
    @Test
    fun readsMultilineRequirementsWithoutOtherCardMetadata() {
        val description = """
            打分结果：良
            预计用时：10
            作业类型：预习
            预习要求：读课文第 3 页。

            写下两个问题。
        """.trimIndent()

        val details = HomeworkTaskDetails.fromDescription(description)

        assertEquals(HomeworkTaskType.PREVIEW, details.type)
        assertEquals("读课文第 3 页。\n\n写下两个问题。", details.task)
        assertEquals(description, HomeworkTaskDetails.displayText(description))
    }

    @Test
    fun emptyRequirementsKeepExplicitPreviewType() {
        val details = HomeworkTaskDetails.fromDescription("type: preview\n预习要求：")

        assertEquals(HomeworkTaskType.PREVIEW, details.type)
        assertNull(details.task)
    }

    @Test
    fun readsRequirementsFromJsonDescriptions() {
        val description = """{"type":"preview","preview_requirements":"Read\nTake notes"}"""

        assertEquals("Read\nTake notes", HomeworkTaskDetails.fromDescription(description).task)
        assertEquals("预习要求：\nRead\nTake notes", HomeworkTaskDetails.displayText(description))
    }

    @Test
    fun appendsRequirementsAndReplacesOnlyTheFieldOnResubmission() {
        val metadata = "打分结果：良\n预计用时：10\n作业类型：预习\n先完成订正。"
        val withRequirements = HomeworkTaskDetails.withPreviewRequirements(metadata, "读课文。\n圈出生字。")
        assertEquals("$metadata\n\n预习要求：\n读课文。\n圈出生字。", withRequirements)

        val updated = HomeworkTaskDetails.withPreviewRequirements(withRequirements, "写下两个问题。")
        assertEquals("$metadata\n\n预习要求：\n写下两个问题。", updated)
        assertEquals(updated, HomeworkTaskDetails.withPreviewRequirements(updated, "写下两个问题。"))
        assertNull(HomeworkTaskDetails.fromDescription(HomeworkTaskDetails.withPreviewRequirements(updated, "")).task)
    }

    @Test
    fun writesRequirementsInTheExistingJsonFormat() {
        val description = """{"type":"preview","task":"保留原说明"}"""
        val updated = HomeworkTaskDetails.withPreviewRequirements(description, "读课文。")

        assertEquals("读课文。", HomeworkTaskDetails.fromDescription(updated).task)
        assertEquals("保留原说明\n预习要求：\n读课文。", HomeworkTaskDetails.displayText(updated))
    }

    @Test(expected = IllegalStateException::class)
    fun refusesToUpdateACardWhoseTypeHasChanged() {
        HomeworkTaskDetails.withPreviewRequirements("作业类型：普通作业", "读课文。")
    }
}
