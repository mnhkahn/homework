package com.homeworkbuddy

import android.content.Context

internal class PreviewRequirementsStore(context: Context) {
    private val prefs = context.getSharedPreferences("preview_requirements", Context.MODE_PRIVATE)

    fun read(): String? = prefs.getString("requirements", DEFAULT_REQUIREMENTS)?.takeIf(String::isNotBlank)

    fun save(value: String) {
        // Keep an explicit empty value so clearing the field disables syncing,
        // while devices without a saved setting use the default instructions.
        prefs.edit().putString("requirements", value.trim()).apply()
    }

    companion object {
        private val DEFAULT_REQUIREMENTS = """
            1. 朗读要学习的内容两遍，读准字音，读通句子。
            2. 圈出不认识的字词，查一查并标注读音。
            3. 想一想主要讲了什么，标出不理解的地方。
            4. 完成后拍照或录音提交。
        """.trimIndent()
    }
}
