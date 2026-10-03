package com.homeworkbuddy

import org.junit.Assert.*
import org.junit.Test

class HomeworkPdfTest {
    @Test fun readsPdfMarkerFromTextAndKeepsTaskContent() {
        val details = HomeworkTaskDetails.fromDescription("类型: pdf_attachment\n作业内容: 完成数学练习\n预计用时: 20")
        assertEquals(HomeworkTaskType.PDF_ATTACHMENT, details.type)
        assertEquals("完成数学练习", details.task)
        assertNull(details.link)
    }

    @Test fun readsPdfMarkerFromJsonWithoutRequiringALink() {
        val details = HomeworkTaskDetails.fromDescription("""{"type":"pdf_attachment","task":"完成附件"}""")
        assertEquals(HomeworkTaskType.PDF_ATTACHMENT, details.type)
        assertEquals("完成附件", details.task)
        assertNull(details.link)
    }

    @Test fun acceptsChineseAndCaseInsensitiveMarkers() {
        listOf("作业类型：附件PDF", "type: PDF_ATTACHMENT").forEach {
            assertEquals(HomeworkTaskType.PDF_ATTACHMENT, HomeworkTaskDetails.fromDescription(it).type)
        }
    }

    @Test fun explicitNormalTypeStillWins() {
        assertEquals(HomeworkTaskType.NORMAL,
            HomeworkTaskDetails.fromDescription("""{"type":"normal","task":"worksheet.pdf"}""").type)
    }

    @Test fun recognizesPdfsWhenTrelloOmitsMimeTypeOrRenamesTheFile() {
        assertTrue(HomeworkAttachment("https://trello.com/file", "数学练习", "application/pdf").isPdf)
        assertTrue(HomeworkAttachment("https://trello.com/file", "worksheet.PDF", "").isPdf)
        assertTrue(HomeworkAttachment("https://trello.com/worksheet.pdf?token=abc", "数学练习", "").isPdf)
        assertFalse(HomeworkAttachment("https://trello.com/photo.jpg", "作业照片", "image/jpeg").isPdf)
        assertFalse(HomeworkAttachment("https://trello.com/audio.mp3", "录音", "audio/mpeg").isPdf)
    }
}
