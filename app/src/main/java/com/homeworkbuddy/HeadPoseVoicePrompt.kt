package com.homeworkbuddy

internal enum class HeadPoseVoicePrompt {
    HEAD_HIGH, HEAD_LOW, TURN_BACK, TOO_CLOSE, TOO_FAR,
}

/** Stable order and a single turn-back prompt even when both yaw warnings overlap. */
internal fun headPoseVoicePrompts(warnings: Set<PoseWarning>): List<HeadPoseVoicePrompt> =
    PoseWarning.entries.filter { it in warnings }.map {
        when (it) {
            PoseWarning.PITCH_HIGH -> HeadPoseVoicePrompt.HEAD_HIGH
            PoseWarning.PITCH_LOW -> HeadPoseVoicePrompt.HEAD_LOW
            PoseWarning.YAW_LOW, PoseWarning.YAW_HIGH -> HeadPoseVoicePrompt.TURN_BACK
            PoseWarning.TOO_CLOSE -> HeadPoseVoicePrompt.TOO_CLOSE
            PoseWarning.TOO_FAR -> HeadPoseVoicePrompt.TOO_FAR
        }
    }.distinct()
