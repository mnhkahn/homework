package com.homeworkbuddy

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal class PlayDemo(context: Context) {
    private val prefs = context.getSharedPreferences("play_demo", Context.MODE_PRIVATE)

    fun tasks(today: LocalDate = LocalDate.now()): List<HomeworkTask> = listOf(
        HomeworkTask(
            id = "demo-$today-arithmetic", subject = "数学", title = "口算练习",
            estimatedMinutes = 5, deadline = LocalTime.of(20, 0), dueDate = today,
            type = HomeworkTaskType.ARITHMETIC, task = "26 + 17 = ___\n54 − 28 = ___\n38 + 25 = ___",
        ),
        HomeworkTask(
            id = "demo-$today-writing", subject = "语文", title = "写一句观察日记",
            estimatedMinutes = 10, deadline = LocalTime.of(20, 30), dueDate = today,
            description = "观察家中的一件物品，用一句话写下它的颜色和形状。可以提交文字、照片或录音。",
        ),
    ).map { task ->
        val completedAt = prefs.getLong(task.id, 0L)
        val submitted = completedAt > 0
        task.copy(
            status = if (submitted) TaskStatus.COMPLETED else TaskStatus.TODO,
            completedAtEpochSeconds = completedAt.takeIf { submitted },
            tableCard = HomeworkTableCard(
                listName = if (submitted) "已完成" else "待完成",
                startAtMillis = null,
                dueAtMillis = today.atTime(task.deadline).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                dueComplete = submitted,
                typeLabel = null,
                gradeLabel = null,
                completedAtMillis = completedAt.takeIf { submitted }?.times(1_000),
                updatedAtMillis = null,
            ),
        )
    }

    fun complete(taskId: String) {
        require(taskId.startsWith("demo-"))
        if (prefs.contains(taskId)) return
        check(prefs.edit().putLong(taskId, Instant.now().epochSecond).commit()) { "示例进度保存失败" }
    }

    companion object {
        const val BINDING_ID = "local-demo"
        const val PRIVACY_URL = "https://homework-buddy-privacy.vercel.app/"

        fun enabled(context: Context): Boolean = BuildConfig.PLAY_DISTRIBUTION &&
            context.getSharedPreferences("play_demo", Context.MODE_PRIVATE).getBoolean("enabled", false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("play_demo", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        }

        fun preferences(context: Context, name: String) = context.getSharedPreferences(
            if (enabled(context)) "demo_$name" else name, Context.MODE_PRIVATE,
        )
    }
}
