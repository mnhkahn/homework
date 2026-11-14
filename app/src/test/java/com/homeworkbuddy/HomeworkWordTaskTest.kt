package com.homeworkbuddy

import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class HomeworkWordTaskTest {
    @Test fun vocabularyContractMatchesCliCreationRules() {
        val cases = listOf(
            "e-reader, game console, GPS, headphones, laptop, smartphone, tablet, VR headset, hungry, ice cream, lemonade, menu, omelette, piece of cake, sandwich, sauce, strawberry, vegetable, waiter" to "e-reader,game console,GPS,headphones,laptop,smartphone,tablet,VR headset,hungry,ice cream,lemonade,menu,omelette,piece of cake,sandwich,sauce,strawberry,vegetable,waiter",
            "post   office，train station, cell phone" to "post office,train station,cell phone",
            "Post(office), CELL(phone)(手机), camera（相机）, don't, well-known, teacher’s" to "Post office,CELL phone,camera,don't,well-known,teacher’s",
            "cell（phone）（手机）" to "cell phone",
            "armchair cushions" to "armchair cushions",
            "bank,, ， museum," to "bank,museum",
            "camera(相机" to null,
            "camera()" to null,
            "camera(123)" to null,
            "hello!" to null,
            "ice cream; cake" to null,
            "VR2" to null,
            "123" to null,
            "你好" to null,
            "https://www.cyeam.com/ai/translate?words=bank" to null,
            "good / bad" to null,
            "-reader" to null,
            "'hello" to null,
            "" to null,
        )
        for ((input, normalized) in cases) {
            val details = HomeworkTaskDetails.fromDescription("作业类型：背单词\n背单词：$input")
            val task = HomeworkTask(
                id = "contract", subject = "英语", title = "背单词", estimatedMinutes = 10,
                deadline = LocalTime.NOON, type = details.type, task = details.task, link = details.link,
            )
            val expected = normalized?.lowercase()?.split(',').orEmpty()
            assertEquals(input, expected, HomeworkTaskDetails.vocabularyWords(task))
        }
    }

    @Test fun lessonNineUsesAllNineteenNativeWordsEvenWithTranslationLinkAndHints() {
        val assignment = "e-reader, game console, GPS, headphones, laptop, smartphone, tablet, VR headset, hungry, ice cream, lemonade, menu, omelette, piece of cake, sandwich, sauce, strawberry, vegetable, waiter"
        val description = """
            预计 20 分钟（估算）；计划时间：2026-11-12 18:40–19:00（北京时间）；按当日截止时间顺序执行。26秋 Lesson 9；下周上课前听写。

            作业类型：背单词
            背单词：$assignment
            翻译链接：https://www.cyeam.com/ai/translate?words=e-reader,game%20console,GPS,headphones,laptop,smartphone,tablet,VR%20headset,hungry,ice%20cream,lemonade,menu,omelette,piece%20of%20cake,sandwich,sauce,strawberry,vegetable,waiter
            中文提示：hungry 饥饿的；ice cream 冰淇淋；lemonade 柠檬水；menu 菜单；omelette 煎蛋卷；piece of cake 小菜一碟；sandwich 三明治；sauce 酱料；strawberry 草莓；vegetable 蔬菜；waiter 服务员
        """.trimIndent()
        val details = HomeworkTaskDetails.fromDescription(description)
        val task = HomeworkTask(
            id = "lesson-nine", subject = "英语", title = "背单词", estimatedMinutes = 20,
            deadline = LocalTime.NOON, type = details.type, task = details.task,
            link = details.link, description = description,
        )
        val expected = assignment.lowercase().split(", ")
        assertEquals(19, expected.size)
        assertEquals(expected, HomeworkTaskDetails.vocabularyWords(task))
        // Older caches can retain only the generated URL as their task content.
        listOf(null, details.link).forEach { cachedContent ->
            assertEquals(expected, HomeworkTaskDetails.vocabularyWords(task.copy(task = cachedContent)))
        }
        assertTrue(HomeworkTaskDetails.vocabularyWords(task.copy(type = HomeworkTaskType.NORMAL)).isEmpty())
    }

    @Test fun wordListWinsOverPlainMarkdownAndJsonLinks() {
        val assignment = "games, friends, biscuit, breakfast, cafeteria, cereal, chilli, chocolate, curry, delicious, dessert, grilled"
        // Deliberately different words: the link must not become the study source.
        val link = "https://www.cyeam.com/ai/translate?words=other,words"
        listOf(
            "作业类型：背单词\n背单词：$assignment\n翻译链接：$link",
            "作业类型：背单词\n背单词：$assignment\n翻译链接：[$link]($link)",
            """{"type":"word_memorization","task":"$assignment","link":"$link"}""",
        ).forEach { description ->
            val details = HomeworkTaskDetails.fromDescription(description)
            val task = HomeworkTask(
                id = "words-with-link", subject = "英语", title = "背单词", estimatedMinutes = 10,
                deadline = LocalTime.NOON, type = details.type, task = details.task,
                link = details.link, description = description,
            )
            assertEquals(link, details.link)
            assertEquals(assignment.split(", "), HomeworkTaskDetails.vocabularyWords(task))
            assertEquals(assignment.split(", "), HomeworkTaskDetails.vocabularyWords(
                task.copy(link = HomeworkTaskDetails.restoreVocabularyLink(task)),
            ))
        }
    }

    @Test fun missingOrInvalidWordListFallsBackToSuppliedLink() {
        val link = "https://www.cyeam.com/ai/translate?words=games,friends"
        listOf(null, "", link, "camera(相机").forEach { content ->
            val task = HomeworkTask(
                id = "link-only", subject = "英语", title = "背单词", estimatedMinutes = 10,
                deadline = LocalTime.NOON, type = HomeworkTaskType.WORD_MEMORIZATION,
                task = content, link = link,
            )
            assertTrue(HomeworkTaskDetails.vocabularyWords(task).isEmpty())
            assertEquals(link, task.link)
        }
    }

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
        assertEquals(listOf("post office", "cell phone", "don't"),
            HomeworkTaskDetails.vocabularyWords(task.copy(link = "http://example.com/words")))

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

    @Test fun typedHttpContentAndBareLegacyVocabularyUrlRetainTheirLinks() {
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
