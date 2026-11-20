package com.homeworkbuddy

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

internal data class RemoteSpeechRequest(val text: String, val language: String, val rate: Float) {
    companion object {
        fun parse(value: JSONObject): RemoteSpeechRequest {
            val text = value.optString("text").trim()
            val language = value.optString("language", "zh-CN")
            val rate = value.optDouble("rate", 1.0).toFloat()
            if (text.isEmpty() || text.codePointCount(0, text.length) > 300) throw McpException(-32602, "播报文字须为 1 至 300 字")
            if (language !in setOf("zh-CN", "en-US")) throw McpException(-32602, "language 必须为 zh-CN 或 en-US")
            if (!rate.isFinite() || rate !in 0.5f..2f) throw McpException(-32602, "rate 须在 0.5 至 2 之间")
            return RemoteSpeechRequest(text, language, rate)
        }
    }
}

/** A successful RPC result means the local TTS engine finished the utterance. */
internal object RemoteSpeech {
    suspend fun speak(context: Context, request: RemoteSpeechRequest): JSONObject {
        var engine: TextToSpeech? = null
        TeacherConnectionService.beginSpeech(context)
        try {
            return withContext(Dispatchers.Main) {
                val ready = CompletableDeferred<Boolean>()
                engine = TextToSpeech(context.applicationContext) { ready.complete(it == TextToSpeech.SUCCESS) }
                if (withTimeoutOrNull(5_000) { ready.await() } != true) throw McpException(-32008, "系统语音引擎不可用")
                val tts = checkNotNull(engine)
                if (tts.setLanguage(Locale.forLanguageTag(request.language)) < TextToSpeech.LANG_AVAILABLE) {
                    throw McpException(-32008, "系统语音引擎未安装所选语言")
                }
                tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                tts.setSpeechRate(request.rate)
                val utterance = UUID.randomUUID().toString()
                val finished = CompletableDeferred<Boolean>()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { if (utteranceId == utterance) finished.complete(true) }
                    @Deprecated("Legacy TTS callback")
                    override fun onError(utteranceId: String?) { if (utteranceId == utterance) finished.complete(false) }
                    override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == utterance) finished.complete(false) }
                })
                if (tts.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, utterance) != TextToSpeech.SUCCESS) {
                    throw McpException(-32008, "语音播报提交失败")
                }
                if (withTimeoutOrNull(35_000) { finished.await() } != true) throw McpException(-32008, "语音播报失败或超时，未自动重播")
                JSONObject().put("completed", true).put("language", request.language).put("utterance_id", utterance)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { engine?.stop(); engine?.shutdown() }
            TeacherConnectionService.endSpeech(context)
        }
    }
}
