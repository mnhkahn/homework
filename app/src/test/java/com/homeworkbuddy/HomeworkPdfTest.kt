package com.homeworkbuddy

import org.junit.Assert.*
import org.junit.Test

class HomeworkPdfTest {
    @Test fun legacyPdfTextBecomesOrdinaryHomeworkAndKeepsTaskContent() {
        val details = HomeworkTaskDetails.fromDescription("类型: pdf_attachment\n作业内容: 完成数学练习\n预计用时: 20")
        assertEquals(HomeworkTaskType.NORMAL, details.type)
        assertEquals("完成数学练习", details.task)
        assertNull(details.link)
    }

    @Test fun legacyPdfJsonBecomesOrdinaryHomeworkWithoutRequiringALink() {
        val details = HomeworkTaskDetails.fromDescription("""{"type":"pdf_attachment","task":"完成附件"}""")
        assertEquals(HomeworkTaskType.NORMAL, details.type)
        assertEquals("完成附件", details.task)
        assertNull(details.link)
    }

    @Test fun acceptsChineseAndCaseInsensitiveMarkers() {
        listOf("作业类型：附件PDF", "type: PDF_ATTACHMENT").forEach {
            assertEquals(HomeworkTaskType.NORMAL, HomeworkTaskDetails.fromDescription(it).type)
        }
    }

    @Test fun legacyPdfMarkerDoesNotInferVocabularyFromEnglishContent() {
        val details = HomeworkTaskDetails.fromDescription("作业类型：附件PDF\n作业内容：games, friends")
        assertEquals(HomeworkTaskType.NORMAL, details.type)
        assertEquals("games, friends", details.task)
    }

    @Test fun homeworkTypesContainOnlyTheFiveLearningModes() {
        assertEquals(
            setOf("NORMAL", "WORD_MEMORIZATION", "ENGLISH_READING", "ARITHMETIC", "PREVIEW"),
            HomeworkTaskType.entries.map { it.name }.toSet(),
        )
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
