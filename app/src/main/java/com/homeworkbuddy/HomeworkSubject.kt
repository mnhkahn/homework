package com.homeworkbuddy

/** Subject is a title decoration, independent of the assignment's interaction mode. */
internal enum class HomeworkSubject(val label: String) {
    CHINESE("语文"), MATH("数学"), ENGLISH("英语"), SPORTS("体育");

    companion object {
        private val explicitSubject = Regex("语文|数学|英语|体育")
        private val englishSubject = Regex("\\b(chinese|math(?:s|ematics)?|english|p\\.?e\\.?)\\b", RegexOption.IGNORE_CASE)

        fun fromTitle(title: String): HomeworkSubject? {
            // Named subjects take priority over activity words such as “阅读”.
            explicitSubject.find(title)?.value?.let { label ->
                return entries.first { it.label == label }
            }
            englishSubject.find(title)?.value?.lowercase()?.let { word ->
                return when {
                    word == "chinese" -> CHINESE
                    word.startsWith("math") -> MATH
                    word == "english" -> ENGLISH
                    else -> SPORTS
                }
            }
            return when {
                listOf("背单词", "单词", "音标", "字母").any(title::contains) -> ENGLISH
                listOf("口算", "竖式", "计算题", "应用题", "乘法", "除法").any(title::contains) -> MATH
                listOf("跳绳", "跑步", "体能", "篮球", "足球", "游泳", "运动").any(title::contains) -> SPORTS
                listOf("生字", "汉字", "拼音", "古诗", "课外书", "作文", "练字").any(title::contains) -> CHINESE
                else -> null
            }
        }
    }
}
