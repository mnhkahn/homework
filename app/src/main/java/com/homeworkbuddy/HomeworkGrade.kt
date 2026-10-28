package com.homeworkbuddy

enum class HomeworkGrade(val label: String, val symbol: String) {
    PERFECT("100", "🏆"), EXCELLENT("优", "★"), GOOD("良", "✓"), FAIR("中", "●"), NEEDS_PRACTICE("差", "△");
    companion object {
        fun parse(value: String?): HomeworkGrade? = entries.firstOrNull { it.label == value }
    }
}

data class HomeworkGradeResult(val grade: HomeworkGrade, val provisional: Boolean = false) {
    val fieldValue: String get() = (if (provisional) "暂评·" else "") + grade.label
    companion object {
        const val FIELD_NAME = "打分结果"
        fun parse(value: String?): HomeworkGradeResult? {
            val text = value?.trim() ?: return null
            val provisional = text.startsWith("暂评·")
            val grade = HomeworkGrade.parse(text.removePrefix("暂评·")) ?: return null
            if (provisional && grade == HomeworkGrade.PERFECT) return null
            return HomeworkGradeResult(grade, provisional)
        }

        fun fromDescription(description: String): HomeworkGradeResult? {
            val firstLine = description.lineSequence().firstOrNull().orEmpty()
            val value = when {
                firstLine.startsWith("$FIELD_NAME：") -> firstLine.removePrefix("$FIELD_NAME：")
                firstLine.startsWith("$FIELD_NAME:") -> firstLine.removePrefix("$FIELD_NAME:")
                else -> return null
            }
            return parse(value)
        }
    }
}
