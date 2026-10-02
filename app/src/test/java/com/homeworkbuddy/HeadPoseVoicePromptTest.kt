package com.homeworkbuddy

import org.junit.Assert.*
import org.junit.Test

class HeadPoseVoicePromptTest {
    @Test fun eachCauseSelectsItsOwnInstruction() {
        val expected = mapOf(
            PoseWarning.PITCH_HIGH to HeadPoseVoicePrompt.HEAD_HIGH,
            PoseWarning.PITCH_LOW to HeadPoseVoicePrompt.HEAD_LOW,
            PoseWarning.YAW_LOW to HeadPoseVoicePrompt.TURN_BACK,
            PoseWarning.YAW_HIGH to HeadPoseVoicePrompt.TURN_BACK,
            PoseWarning.TOO_CLOSE to HeadPoseVoicePrompt.TOO_CLOSE,
            PoseWarning.TOO_FAR to HeadPoseVoicePrompt.TOO_FAR,
        )
        expected.forEach { (reason, prompt) -> assertEquals(listOf(prompt), headPoseVoicePrompts(setOf(reason))) }
    }
    @Test fun multipleCausesHaveStableOrderWithoutDuplicateTurnInstruction() {
        assertEquals(listOf(HeadPoseVoicePrompt.HEAD_LOW, HeadPoseVoicePrompt.TURN_BACK, HeadPoseVoicePrompt.TOO_CLOSE),
            headPoseVoicePrompts(linkedSetOf(PoseWarning.TOO_CLOSE, PoseWarning.YAW_HIGH, PoseWarning.PITCH_LOW, PoseWarning.YAW_LOW)))
    }
    @Test fun noWarningMeansNoSpeech() { assertTrue(headPoseVoicePrompts(emptySet()).isEmpty()) }
}
