package com.homeworkbuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeworkSubjectTest {
    @Test fun schoolAndWorkbookTitlesIdentifySubjectsWithoutTaskModes() {
        mapOf(
            "Y（语文）学校作业（2026-11-03）" to HomeworkSubject.CHINESE,
            "五三天天练·语文写两页" to HomeworkSubject.CHINESE,
            "S（数学）学校作业" to HomeworkSubject.MATH,
            "五三天天练·数学写两页" to HomeworkSubject.MATH,
            "E（英语）学校作业" to HomeworkSubject.ENGLISH,
            "体育：跳绳十分钟" to HomeworkSubject.SPORTS,
        ).forEach { (title, expected) -> assertEquals(title, expected, HomeworkSubject.fromTitle(title)) }
    }

    @Test fun activityTitlesAndEnglishLabelsHaveSubjectDecorations() {
        mapOf(
            "口算20题（要求列竖式，必须全对）" to HomeworkSubject.MATH,
            "背单词 KET Education" to HomeworkSubject.ENGLISH,
            "阅读课外书一篇" to HomeworkSubject.CHINESE,
            "跳绳10分钟" to HomeworkSubject.SPORTS,
            "English reading" to HomeworkSubject.ENGLISH,
            "Math homework" to HomeworkSubject.MATH,
            "PE homework" to HomeworkSubject.SPORTS,
        ).forEach { (title, expected) -> assertEquals(title, expected, HomeworkSubject.fromTitle(title)) }
    }

    @Test fun explicitSubjectWinsAndUnrelatedTitlesStayUnclassified() {
        assertEquals(HomeworkSubject.ENGLISH, HomeworkSubject.fromTitle("英语：阅读课外书一篇"))
        assertEquals(HomeworkSubject.CHINESE, HomeworkSubject.fromTitle("语文：抄写含英语字母的句子"))
        assertNull(HomeworkSubject.fromTitle("练琴20分钟"))
        assertNull(HomeworkSubject.fromTitle("完成学校作业"))
    }
}
