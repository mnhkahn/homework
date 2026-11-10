package com.homeworkbuddy

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

internal class VocabularyAudio(context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(context.applicationContext) { ready.complete(it == TextToSpeech.SUCCESS) }
    private var utteranceNumber = 0

    suspend fun playWord(entry: VocabularyEntry, rate: Float): Boolean {
        val recording = entry.audioUrl.toHttpUrlOrNull()?.takeIf { it.isHttps }
        val youdao = "https://dict.youdao.com/dictvoice".toHttpUrl().newBuilder()
            .addQueryParameter("type", "2").addQueryParameter("audio", entry.word).build().toString()
        if (recording != null) {
            val replacesDictionary = recording.host == "dictionaryapi.dev" || recording.host.endsWith(".dictionaryapi.dev")
            val preferred = if (replacesDictionary) youdao else recording.toString()
            if (playRecording(preferred, rate)) return true
            if (!replacesDictionary && recording.host != "dict.youdao.com" && !entry.word.contains(' ') &&
                playRecording(youdao, rate)) return true
        }
        if (speak(entry.word, Locale.US, rate)) return true
        val fallback = "https://www.cyeam.com/api/tts".toHttpUrl().newBuilder()
            .addQueryParameter("text", entry.word).build()
        return playRecording(fallback.toString(), rate)
    }

    suspend fun dictate(entry: VocabularyEntry): Boolean {
        entry.translations.firstOrNull()?.let { chinese ->
            for (repeat in 0 until 3) {
                if (!speak(chinese, Locale.SIMPLIFIED_CHINESE, 1f)) break
                delay(400)
            }
        }
        repeat(3) { index ->
            if (!playWord(entry, 0.75f)) return false
            if (index < 2) delay(400)
        }
        return true
    }

    private suspend fun playRecording(url: String, rate: Float): Boolean {
        val player = MediaPlayer()
        val complete = CompletableDeferred<Boolean>()
        return try {
            player.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            player.setOnCompletionListener { complete.complete(true) }
            player.setOnErrorListener { _, _, _ -> complete.complete(false); true }
            player.setOnPreparedListener {
                runCatching {
                    it.playbackParams = it.playbackParams.setSpeed(rate)
                    it.start()
                }.onFailure { complete.complete(false) }
            }
            player.setDataSource(url)
            player.prepareAsync()
            withTimeoutOrNull(12_000) { complete.await() } == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            player.release()
        }
    }

    private suspend fun speak(text: String, language: Locale, rate: Float): Boolean {
        if (withTimeoutOrNull(5_000) { ready.await() } != true) return false
        if (tts.setLanguage(language) < TextToSpeech.LANG_AVAILABLE) return false
        val id = "vocabulary-${++utteranceNumber}"
        val complete = CompletableDeferred<Boolean>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (utteranceId == id) complete.complete(true) }
            @Deprecated("Legacy Android TTS error callback")
            override fun onError(utteranceId: String?) { if (utteranceId == id) complete.complete(false) }
        })
        try {
            tts.setSpeechRate(rate)
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) return false
            return withTimeoutOrNull(15_000) { complete.await() } == true
        } finally {
            tts.stop()
        }
    }

    fun close() {
        tts.stop()
        tts.shutdown()
    }
}
