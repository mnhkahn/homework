package com.homeworkbuddy

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Streams a server-hosted TTS/audio response without first storing it on the tablet. */
object RemoteAudioPlayer {
    private var player: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    suspend fun play(context: Context, rawURL: String): JSONObject {
        val uri = Uri.parse(rawURL)
        require(uri.scheme == "https" || uri.scheme == "http") { "音频 URL 必须使用 HTTP 或 HTTPS" }
        require(!uri.host.isNullOrBlank()) { "音频 URL 缺少主机名" }

        val result = CompletableDeferred<JSONObject>()
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()

        synchronized(this) {
            releaseLocked()
            check(manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "无法取得音频播放焦点" }
            audioManager = manager
            focusRequest = request
            try {
                player = MediaPlayer().also { media ->
                    media.setAudioAttributes(attributes)
                    media.setOnPreparedListener {
                        it.start()
                        result.complete(JSONObject().put("playing", true).put("url", rawURL))
                    }
                    media.setOnCompletionListener { finished ->
                        synchronized(this) { if (player === finished) releaseLocked() }
                    }
                    media.setOnErrorListener { failed, _, _ ->
                        if (!result.isCompleted) result.completeExceptionally(IllegalStateException("音频播放失败"))
                        synchronized(this) { if (player === failed) releaseLocked() }
                        true
                    }
                    media.setDataSource(context, uri)
                    media.prepareAsync()
                }
            } catch (error: Throwable) {
                releaseLocked()
                throw error
            }
        }
        return try {
            withTimeout(15_000) { result.await() }
        } catch (error: Throwable) {
            synchronized(this) { releaseLocked() }
            throw error
        }
    }

    fun stop(context: Context): JSONObject = synchronized(this) {
        val wasPlaying = player != null
        releaseLocked()
        JSONObject().put("stopped", wasPlaying)
    }

    private fun releaseLocked() {
        player?.runCatching { stop(); reset(); release() }
        player = null
        audioManager?.let { manager -> focusRequest?.let(manager::abandonAudioFocusRequest) }
        audioManager = null
        focusRequest = null
    }
}
