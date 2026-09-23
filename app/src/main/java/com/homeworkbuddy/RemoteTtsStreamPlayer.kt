package com.homeworkbuddy

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** Receives the Xiaoli direct-device `tts` control frames and raw Opus packets. */
object RemoteTtsStreamPlayer {
    private val executor = Executors.newSingleThreadExecutor()
    private var sessionId: String? = null
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var framesReceived = 0
    private var framesDecoded = 0
    private var report: ((org.json.JSONObject) -> Unit)? = null

    fun start(context: Context, id: String, statusReporter: (org.json.JSONObject) -> Unit) = executor.execute {
        stopInternal(reportStopped = false)
        try {
            RemoteAudioPlayer.stop(context)
            sessionId = id
            report = statusReporter
            framesReceived = 0
            framesDecoded = 0
            val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0) { "设备不支持单声道 PCM 音频" }
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minBuffer * 4, 16 * 1024))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build().also { it.play() }
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).also { decoder ->
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
                    // Raw WebSocket packets do not carry an Ogg OpusHead page.
                    setByteBuffer("csd-0", ByteBuffer.wrap(OPUS_HEAD))
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 * 1024)
                }
                decoder.configure(format, null, null, 0)
                decoder.start()
            }
        } catch (error: Throwable) {
            sendStatus("error", error.message ?: "无法初始化 Opus 播放器")
            stopInternal(reportStopped = false)
        }
    }

    fun offer(packet: ByteArray) = executor.execute {
        val decoder = codec ?: return@execute
        try {
            val inputIndex = decoder.dequeueInputBuffer(10_000)
            if (inputIndex < 0) return@execute
            decoder.getInputBuffer(inputIndex)?.apply {
                clear()
                put(packet)
            } ?: return@execute
            decoder.queueInputBuffer(inputIndex, 0, packet.size, framesReceived * FRAME_DURATION_US, 0)
            framesReceived++
            drain(decoder)
            // Report the initial five-packet prebuffer, then periodic progress.
            if (framesReceived == 5 || (framesReceived > 5 && framesReceived % 50 == 0)) sendStatus("playing")
        } catch (error: Throwable) {
            sendStatus("error", error.message ?: "Opus 帧解码失败")
            stopInternal(reportStopped = false)
        }
    }

    fun stop() = executor.execute { stopInternal(reportStopped = true) }
    fun reset() = executor.execute { stopInternal(reportStopped = false) }

    private fun drain(decoder: MediaCodec) {
        val info = BufferInfo()
        while (true) {
            when (val outputIndex = decoder.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    decoder.getOutputBuffer(outputIndex)?.let { output ->
                        if (info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            track?.write(output, info.size, AudioTrack.WRITE_BLOCKING)
                            framesDecoded++
                        }
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    }

    private fun stopInternal(reportStopped: Boolean) {
        if (reportStopped && sessionId != null) sendStatus("stopped")
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { track?.pause(); track?.flush(); track?.release() }
        track = null
        sessionId = null
        report = null
    }

    private fun sendStatus(state: String, error: String? = null) {
        val id = sessionId ?: return
        report?.invoke(org.json.JSONObject()
            .put("type", "tts")
            .put("state", state)
            .put("session_id", id)
            .put("frames_received", framesReceived)
            .put("frames_decoded", framesDecoded)
            .apply { error?.let { put("error", it) } })
    }

    private const val SAMPLE_RATE = 16_000
    private const val FRAME_DURATION_US = 60_000L
    private val OPUS_HEAD = byteArrayOf(
        0x4f, 0x70, 0x75, 0x73, 0x48, 0x65, 0x61, 0x64, // OpusHead
        1, 1, 0, 0, 0x80.toByte(), 0x3e, 0, 0, 0, 0, 0,
    )
}
