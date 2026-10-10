package com.homeworkbuddy

import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class HomeworkWordTaskTest {
    @Test fun todaysAnnotatedVocabularyProducesUsableStudyLink() {
        val assignment = "bank, chemist, library, museum, park, post(office), restaurant, supermarket, train(station), camera(相机), cell(phone)(手机), computer(电脑), email(邮件), information(信息), file(文件), photograph(相片), printer(打印机), website(网站)"
        val details = HomeworkTaskDetails.fromDescription("作业类型：背单词\n背单词：$assignment\n翻译链接：https://www.cyeam.com/ai/translate?words=$assignment")
        val expected = "bank,chemist,library,museum,park,post office,restaurant,supermarket,train station,camera,cell phone,computer,email,information,file,photograph,printer,website"
        assertEquals(HomeworkTaskType.WORD_MEMORIZATION, details.type)
        assertEquals(expected, details.task)
        assertEquals(expected, URLDecoder.decode(details.link!!.substringAfter("words="), "UTF-8"))
        assertEquals(18, details.task!!.split(',').size)
    }

    @Test fun fullWidthAnnotationsAndJsonAssignmentsAreSupported() {
        val details = HomeworkTaskDetails.fromDescription("""{"type":"word_memorization","task":"camera（相机），cell（phone）（手机）"}""")
        assertEquals("camera,cell phone", details.task)
        assertNotNull(details.link)
    }

    @Test fun plainVocabularyAndPunctuationKeepExistingBehavior() {
        val details = HomeworkTaskDetails.fromDescription("作业类型：背单词\n背单词：bedroom，armchair, don't, well-known")
        assertEquals("bedroom,armchair,don't,well-known", details.task)
        assertEquals(details.task, URLDecoder.decode(details.link!!.substringAfter("words="), "UTF-8"))
    }

    @Test fun commaDelimitedPhrasesKeepInternalSpacesInEncodedUrl() {
        val details = HomeworkTaskDetails.fromDescription("作业类型：背单词\n背单词：bank, post   office，train station, cell phone")
        assertEquals("bank,post office,train station,cell phone", details.task)
        assertEquals("https://www.cyeam.com/ai/translate?words=bank,post+office,train+station,cell+phone", details.link)
    }

    @Test fun phraseWithoutCommasIsOneEntry() {
        val details = HomeworkTaskDetails.fromDescription("作业类型：背单词\n背单词：post office")
        assertEquals("post office", details.task)
        assertNotNull(details.link)
    }

    @Test fun malformedAnnotationsDoNotBecomeStudyLinks() {
        listOf("camera(相机", "camera()", "camera(123)", "https://example.com").forEach { value ->
            assertNull(HomeworkTaskDetails.fromDescription("作业类型：背单词\n背单词：$value").link)
        }
    }

    @Test fun explicitNormalTaskIsNotConvertedToVocabulary() {
        val details = HomeworkTaskDetails.fromDescription("作业类型：普通作业\n作业内容：camera(相机)")
        assertEquals(HomeworkTaskType.NORMAL, details.type)
        assertNull(details.link)
    }
}
