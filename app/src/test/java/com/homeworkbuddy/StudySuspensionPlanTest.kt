package com.homeworkbuddy

import org.junit.Assert.assertEquals
import org.junit.Test

class StudySuspensionPlanTest {
    @Test fun repeatedEnforcementNeverTemporarilyReleasesBlockedApps() {
        val plan = studySuspensionPlan(setOf("douyin", "other"), setOf("douyin", "reader"), emptySet(), setOf("reader"))
        assertEquals(setOf("douyin", "other"), plan.toSuspend)
        assertEquals(emptySet<String>(), plan.toResume)
    }

    @Test fun onlyNewlyAllowedPackagesAreReleased() {
        val plan = studySuspensionPlan(setOf("douyin", "reader"), setOf("reader"), emptySet(), setOf("reader"))
        assertEquals(setOf("douyin"), plan.toSuspend)
        assertEquals(setOf("reader"), plan.toResume)
    }

    @Test fun unapprovedBrowsersStayBlockedEvenIfMarkedAllowed() {
        val plan = studySuspensionPlan(emptySet(), setOf("browser", "chrome"), setOf("browser"), setOf("browser", "chrome"))
        assertEquals(setOf("browser"), plan.toSuspend)
        assertEquals(emptySet<String>(), plan.toResume)
    }
}
