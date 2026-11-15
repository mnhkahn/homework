package com.homeworkbuddy

import org.junit.Assert.*
import org.junit.Test

class ReadingTextTest {
    @Test fun readsOriginalWithoutTreatingItAsMetadata() {
        val description = "作业类型：阅读英语\n读单词：tablet, power\n\n英语原文：\nDo you need a break?\na never\nb sometimes"
        assertEquals("Do you need a break?\na never\nb sometimes", HomeworkTaskDetails.readingText(description))
        val details = HomeworkTaskDetails.fromDescription(description)
        assertEquals(HomeworkTaskType.ENGLISH_READING, details.type)
        assertEquals("tablet,power", details.task)
        assertNull(details.link)
        val task = HomeworkTask("reading", "英语", "阅读", 10, java.time.LocalTime.NOON,
            type = details.type, task = details.task, description = description)
        assertEquals(listOf("tablet", "power"), HomeworkTaskDetails.vocabularyWords(task))
        assertEquals("听三遍", HomeworkTaskDetails.requirements("作业要求：听三遍\n\n$description"))
    }
    @Test fun separatesQuestionsAndChoicesAndPreservesDecimals() {
        assertEquals(listOf("DO YOU NEED A TECH BREAK?", "How many hours?", "a less than two", "b between two and four", "It costs 15.00.", "Really?"),
            readingSentences("DO YOU NEED A TECH BREAK?\n\nHow many hours?\na less than two\nb between two and four\nIt costs 15.00. Really?"))
    }
    @Test fun ignoresLegacyTranslationsWhenExtractingOriginal() {
        val description = "作业类型：阅读英语\n英语原文：\nHello! How are you?\n\n英语译文：\n你好！\n你好吗？"
        assertEquals("Hello! How are you?", HomeworkTaskDetails.readingText(description))
        assertEquals(2, readingSentences(HomeworkTaskDetails.readingText(description)!!).size)
    }
    @Test fun acceptsJsonAndLegacyCards() {
        assertEquals("Hello!", HomeworkTaskDetails.readingText("""{"type":"english_reading","reading_text":"Hello!"}"""))
        assertNull(HomeworkTaskDetails.readingText("作业类型：阅读英语\n阅读英语链接：https://www.cyeam.com/ai/translate?article=1"))
    }
}
