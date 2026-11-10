package com.homeworkbuddy

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class VocabularyEntry(
    val word: String,
    val phonetic: String = "",
    val audioUrl: String = "",
    val translations: List<String> = emptyList(),
    val definitions: List<String> = emptyList(),
    val chunks: List<String> = emptyList(),
    val stress: Int = -1,
) {
    val hasMeanings: Boolean get() = translations.isNotEmpty() || definitions.isNotEmpty()
}

internal class VocabularyDictionary(private val context: Context) {
    private val prefs = context.getSharedPreferences("vocabulary_dictionary", Context.MODE_PRIVATE)

    suspend fun load(words: List<String>): List<VocabularyEntry> = coroutineScope {
        val chunks = async(io) { loadChunks(words.toSet()) }.await()
        words.map { word ->
            async(io) {
                val entry = lookup(word)
                val hint = chunks[word]
                if (hint == null) entry else entry.copy(chunks = hint.first, stress = hint.second)
            }
        }.awaitAll()
    }

    private fun loadChunks(words: Set<String>): Map<String, Pair<List<String>, Int>> =
        context.assets.open("word_study_chunks.txt").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val stress = line.substringBefore('\t').toIntOrNull() ?: return@mapNotNull null
                val parts = line.substringAfter('\t').split('.')
                val word = parts.joinToString("")
                if (word in words) word to (parts to stress) else null
            }.toMap()
        }

    private fun lookup(word: String): VocabularyEntry {
        prefs.getString(word, null)?.let { cached ->
            parse(word, cached)?.takeIf { it.hasMeanings }?.let { return it }
        }
        val url = (GameActivity.ENGLISH_READING_URL + "/api").toHttpUrl().newBuilder()
            .addQueryParameter("text", word).build()
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val raw = response.body?.string()
                val entry = if (response.isSuccessful && raw != null) parse(word, raw) else null
                if (entry?.hasMeanings == true) prefs.edit().putString(word, raw).apply()
                entry ?: VocabularyEntry(word)
            }
        } catch (_: IOException) {
            VocabularyEntry(word)
        }
    }

    private fun parse(word: String, raw: String): VocabularyEntry? {
        val value = runCatching { JSONObject(raw).optJSONObject("wordTranslate") }.getOrNull() ?: return null
        val phonetics = value.optJSONObject("phonetics")
        val translations = mutableListOf<String>()
        val definitions = mutableListOf<String>()
        val meanings = value.optJSONArray("meanings")
        for (index in 0 until (meanings?.length() ?: 0)) {
            val meaning = meanings?.optJSONObject(index) ?: continue
            val definition = meaning.optString("definition").trim()
            if (definition.isEmpty()) continue
            val partOfSpeech = meaning.optString("partOfSpeech").trim()
            if (partOfSpeech.isEmpty()) translations += definition
            else definitions += "[$partOfSpeech] $definition"
        }
        return VocabularyEntry(
            word = word,
            phonetic = phonetics?.optString("text").orEmpty().trim().takeUnless { it.equals(word, true) }.orEmpty(),
            audioUrl = phonetics?.optString("audio").orEmpty(),
            translations = translations,
            definitions = definitions,
        )
    }

    companion object {
        private val io = Dispatchers.IO.limitedParallelism(6)
        private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    }
}
