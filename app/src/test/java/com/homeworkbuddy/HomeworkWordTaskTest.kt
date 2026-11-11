package com.homeworkbuddy

import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class HomeworkWordTaskTest {
    @Test fun englishReadingRecognizesTheArticleAndTheReadAloudWordsSeparately() {
        val description = """
            作业类型：阅读英语
            阅读英语链接：/ai/translate?textbook=0&article=4
            读单词：Robotics, build our own, robotics
        """.trimIndent()
        val details = HomeworkTaskDetails.fromDescription(description)
        assertEquals(HomeworkTaskType.ENGLISH_READING, details.type)
        assertEquals("https://www.cyeam.com/ai/translate?textbook=0&article=4", details.link)
        val task = HomeworkTask(
            id = "reading", subject = "英语", title = "阅读", estimatedMinutes = 10,
            deadline = LocalTime.NOON, type = details.type, task = details.task, link = details.link,
        )
        assertEquals(listOf("robotics", "build our own"), HomeworkTaskDetails.vocabularyWords(task))

        val legacy = HomeworkTaskDetails.fromDescription(description.substringBefore("\n读单词"))
        assertEquals(details.link, legacy.link)
        assertTrue(HomeworkTaskDetails.vocabularyWords(task.copy(task = legacy.task)).isEmpty())
    }

    @Test fun nativeStudyKeepsWholePhrasesAndDeduplicatesLikeTheWebPage() {
        val task = HomeworkTask(
            id = "vocabulary",
            subject = "英语",
            title = "背单词",
            estimatedMinutes = 10,
            deadline = LocalTime.NOON,
            type = HomeworkTaskType.WORD_MEMORIZATION,
            task = "Post(office), post office, CELL(phone)(手机), don't",
        )
        assertEquals(listOf("post office", "cell phone", "don't"), HomeworkTaskDetails.vocabularyWords(task))
        assertTrue(HomeworkTaskDetails.vocabularyWords(task.copy(type = HomeworkTaskType.NORMAL)).isEmpty())
        assertTrue(HomeworkTaskDetails.vocabularyWords(task.copy(link = "http://example.com/words")).isEmpty())

        val oldGeneratedLink = "https://www.cyeam.com/ai/translate?words=post+office"
        val cached = task.copy(link = oldGeneratedLink, description = "作业类型：背单词\n背单词：post office")
        assertNull(HomeworkTaskDetails.restoreVocabularyLink(cached))
        assertEquals(oldGeneratedLink, HomeworkTaskDetails.restoreVocabularyLink(
            cached.copy(description = cached.description + "\n翻译链接：" + oldGeneratedLink),
        ))
    }

    @Test fun annotatedVocabularyRetainsTheExplicitLegacyLink() {
        val assignment = "bank, chemist, library, museum, park, post(office), restaurant, supermarket, train(station), camera(相机), cell(phone)(手机), computer(电脑), email(邮件), information(信息), file(文件), photograph(相片), printer(打印机), website(网站)"
        val url = "https://www.cyeam.com/ai/translate?words=bank,post%20office"
        val details = HomeworkTaskDetails.fromDescription("作业类型：背单词\n背单词：$assignment\n翻译链接：$url")
        val expected = "bank,chemist,library,museum,park,post office,restaurant,supermarket,train station,camera,cell phone,computer,email,information,file,photograph,printer,website"
        assertEquals(HomeworkTaskType.WORD_MEMORIZATION, details.type)
        assertEquals(expected, details.task)
        assertEquals(url, details.link)
        assertEquals(18, details.task!!.split(',').size)
    }

    @Test fun fullWidthAnnotationsAndJsonAssignmentsAreSupported() {
        val details = HomeworkTaskDetails.fromDescription("""{"type":"word_memorization","task":"camera（相机），cell（phone）（手机）"}""")
        assertEquals("camera,cell phone", details.task)
        assertNull(details.link)
    }

    @Test fun plainVocabularyAndPunctuationKeepExistingBehavior() {
        val details = HomeworkTaskDetails.fromDescription("作业类型：背单词\n背单词：bedroom，armchair, don't, well-known")
        assertEquals("bedroom,armchair,don't,well-known", details.task)
        assertNull(details.link)
    }

    @Test fun commaDelimitedPhrasesKeepInternalSpacesInNativeTask() {
        val details = HomeworkTaskDetails.fromDescription("作业类型：背单词\n背单词：bank, post   office，train station, cell phone")
        assertEquals("bank,post office,train station,cell phone", details.task)
        assertNull(details.link)
    }

    @Test fun phraseWithoutCommasIsOneEntry() {
        val details = HomeworkTaskDetails.fromDescription("作业类型：背单词\n背单词：post office")
        assertEquals("post office", details.task)
        assertNull(details.link)
    }

    @Test fun malformedAnnotationsDoNotBecomeStudyWords() {
        listOf("camera(相机", "camera()", "camera(123)").forEach { value ->
            assertNull(HomeworkTaskDetails.fromDescription("作业类型：背单词\n背单词：$value").link)
        }
    }

    @Test fun typedHttpContentAndBareLegacyVocabularyUrlRemainWebTasks() {
        val http = "http://example.com/study?unit=4"
        assertEquals(http, HomeworkTaskDetails.fromDescription("作业类型：背单词\n背单词：$http").link)
        assertEquals(http, HomeworkTaskDetails.fromDescription(
            """{"type":"word_memorization","task":"train station","link":"$http"}""",
        ).link)

        val legacy = "https://www.cyeam.com/ai/translate?word=train+station&word=airport"
        assertEquals(HomeworkTaskDetails(HomeworkTaskType.WORD_MEMORIZATION, link = legacy),
            HomeworkTaskDetails.fromDescription(legacy))
    }

    @Test fun explicitNormalTaskIsNotConvertedToVocabulary() {
        val details = HomeworkTaskDetails.fromDescription("作业类型：普通作业\n作业内容：camera(相机)")
        assertEquals(HomeworkTaskType.NORMAL, details.type)
        assertNull(details.link)
    }
}
