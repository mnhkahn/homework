package com.homeworkbuddy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyForegroundGuardTest {
    private val managed = "com.homeworkbuddy"

    @Test fun allowlistedRecorderRemainsAllowedWithoutLaunchFlag() {
        assertTrue(isAllowedExternalStudyApp(ForegroundApp("com.android.soundrecorder", true, true), managed))
    }

    @Test fun blockedAppsAndHomeStillRequireRestoration() {
        assertFalse(isAllowedExternalStudyApp(ForegroundApp("blocked.app", true, false), managed))
        assertFalse(isAllowedExternalStudyApp(ForegroundApp("system.home", false, true), managed))
        assertFalse(isAllowedExternalStudyApp(ForegroundApp(managed, true, true), managed))
        assertFalse(isAllowedExternalStudyApp(null, managed))
    }
}
