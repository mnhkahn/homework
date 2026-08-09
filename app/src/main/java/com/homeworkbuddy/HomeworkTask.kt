package com.homeworkbuddy

import java.time.LocalTime
import java.time.LocalDate

enum class TaskStatus { TODO, RUNNING, COMPLETED, OVERTIME }

data class HomeworkTask(
    val id: String,
    val subject: String,
    val title: String,
    val estimatedMinutes: Int,
    val deadline: LocalTime,
    val status: TaskStatus = TaskStatus.TODO,
    val photoPath: String? = null,
    /** The Trello attachment URLs are full-resolution originals. */
    val photoUrls: List<String> = emptyList(),
    val dueDate: LocalDate = LocalDate.now(),
    val completedAtEpochSeconds: Long? = null,
)

/** Legacy preview abstraction; production homework sync is implemented by [HomeworkApi]. */
interface HomeworkTaskSource {
    suspend fun today(): List<HomeworkTask>
    suspend fun submit(task: HomeworkTask, photoPath: String?): HomeworkTask
}

class PreviewTaskSource : HomeworkTaskSource {
    private val tasks = listOf(
        HomeworkTask("chinese", "语文", "抄写生字第 1—3 课", 15, LocalTime.of(18, 30), TaskStatus.COMPLETED),
        HomeworkTask("math", "数学", "完成口算练习册第 12 页", 20, LocalTime.of(19, 0)),
        HomeworkTask("english", "英语", "朗读 Unit 3 单词", 25, LocalTime.of(20, 0)),
    )
    override suspend fun today() = tasks
    override suspend fun submit(task: HomeworkTask, photoPath: String?) = task.copy(status = TaskStatus.COMPLETED, photoPath = photoPath)
}
