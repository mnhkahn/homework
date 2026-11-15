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

    @Test fun internalPagesNeverNeedLaunchFlags() {
        assertFalse(shouldRestoreStudyTask(true, false, ForegroundApp(managed, true, true), managed))
        assertFalse(shouldRestoreStudyTask(true, false, null, managed))
        assertFalse(shouldRestoreStudyTask(true, false, ForegroundApp("system.home", false, true), managed))
    }

    @Test fun homeAndBlockedAppsAreRestoredEvenWithStaleOwnPackageEvent() {
        assertTrue(shouldRestoreStudyTask(false, false, ForegroundApp(managed, true, true), managed))
        assertTrue(shouldRestoreStudyTask(false, false, ForegroundApp("system.home", false, true), managed))
        assertTrue(shouldRestoreStudyTask(false, false, ForegroundApp("blocked.app", true, false), managed))
        assertTrue(shouldRestoreStudyTask(false, false, null, managed))
    }

    @Test fun authorizedExternalSessionsAndAllowlistedAppsRemainOpen() {
        assertFalse(shouldRestoreStudyTask(false, true, null, managed))
        assertFalse(shouldRestoreStudyTask(false, false, ForegroundApp("com.android.soundrecorder", true, true), managed))
    }

}
