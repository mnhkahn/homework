package com.homeworkbuddy

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/** Receives the Xiaoli direct-device `tts` control frames and raw Opus packets. */
object RemoteTtsStreamPlayer {
    private val executor = Executors.newSingleThreadExecutor()
    private var sessionId: String? = null
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var framesReceived = 0
    private var framesDecoded = 0
    private var pcmFramesWritten = 0L
    private var bytesPerFrame = 2
    private var report: ((org.json.JSONObject) -> Unit)? = null

    fun start(context: Context, id: String, statusReporter: (org.json.JSONObject) -> Unit) = executor.execute {
        release()
        try {
            RemoteAudioPlayer.stop(context)
            sessionId = id
            report = statusReporter
            framesReceived = 0
            framesDecoded = 0
            pcmFramesWritten = 0
            // Assign before configure/start so initialization failures also release the codec.
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            codec = decoder
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
                // Android requires all three Opus CSD buffers, even for raw packets.
                setByteBuffer("csd-0", ByteBuffer.wrap(OPUS_HEAD))
                setByteBuffer("csd-1", nativeLong(0))
                setByteBuffer("csd-2", nativeLong(80_000_000))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 * 1024)
            }
            decoder.configure(format, null, null, 0)
            decoder.start()
        } catch (error: Exception) {
            fail(error, "无法初始化 Opus 播放器")
        }
    }

    fun offer(packet: ByteArray) = executor.execute {
        val decoder = codec ?: return@execute
        try {
            val inputIndex = awaitInput(decoder)
            val input = checkNotNull(decoder.getInputBuffer(inputIndex)) { "Opus 输入缓冲不可用" }
            input.clear()
            input.put(packet)
            decoder.queueInputBuffer(inputIndex, 0, packet.size, framesReceived * FRAME_DURATION_US, 0)
            framesReceived++
            drain(decoder)
        } catch (error: Exception) {
            fail(error, "Opus 帧解码失败")
        }
    }

    // The server sends stop after its last packet. Finish decoding and playback first.
    fun stop() = executor.execute {
        val decoder = codec ?: return@execute
        try {
            val inputIndex = awaitInput(decoder)
            decoder.queueInputBuffer(inputIndex, 0, 0, framesReceived * FRAME_DURATION_US, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (!drain(decoder, 10_000)) {
                check(SystemClock.elapsedRealtime() < deadline) { "等待 Opus 解码结束超时" }
            }
            check(framesReceived == 0 || framesDecoded > 0) { "收到 $framesReceived 帧音频，但没有解码输出" }
            awaitPlayback()
            sendStatus("stopped")
            release()
        } catch (error: Exception) {
            fail(error, "音频播放结束失败")
        }
    }

    fun reset() = executor.execute { release() }

    private fun awaitInput(decoder: MediaCodec): Int {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (true) {
            val index = decoder.dequeueInputBuffer(10_000)
            if (index >= 0) return index
            // Backpressure must drain output, never silently discard an input packet.
            drain(decoder)
            check(SystemClock.elapsedRealtime() < deadline) { "等待 Opus 输入缓冲超时" }
        }
    }

    /** Returns true only after the decoder's end-of-stream output. */
    private fun drain(decoder: MediaCodec, timeoutUs: Long = 0): Boolean {
        val info = BufferInfo()
        while (true) {
            when (val index = decoder.dequeueOutputBuffer(info, timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> configureTrack(decoder.outputFormat)
                else -> if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val output = checkNotNull(decoder.getOutputBuffer(index)) { "Opus 输出缓冲不可用" }
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            writePcm(output)
                            framesDecoded++
                            if (framesDecoded == 1 || framesDecoded % 50 == 0) sendStatus("playing")
                        }
                    } finally {
                        decoder.releaseOutputBuffer(index, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    private fun configureTrack(format: MediaFormat) {
        check(track == null) { "播放过程中 PCM 格式发生变化" }
        // Opus decoders may output 48 kHz regardless of the input header's rate.
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
        check(encoding == AudioFormat.ENCODING_PCM_16BIT) { "不支持的 PCM 编码：$encoding" }
        val mask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> error("不支持的 PCM 声道数：$channels")
        }
        bytesPerFrame = channels * 2
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, mask, encoding)
        check(minBuffer > 0) { "设备不支持解码后的 PCM 格式" }
        val audio = AudioTrack.Builder()
            // Match URL playback and use media volume rather than the system assistant's volume.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(mask).setEncoding(encoding).build())
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = audio
        check(audio.state == AudioTrack.STATE_INITIALIZED) { "无法初始化音频输出" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.setStartThresholdInFrames(1)
        audio.play()
        Log.i(TAG, "Opus output: sampleRate=$sampleRate channels=$channels")
    }

    private fun writePcm(buffer: ByteBuffer) {
        val audio = checkNotNull(track) { "解码器尚未提供 PCM 格式" }
        val size = buffer.remaining()
        check(size % bytesPerFrame == 0) { "PCM 数据未按采样帧对齐" }
        var deadline = SystemClock.elapsedRealtime() + 5_000
        while (buffer.hasRemaining()) {
            val written = audio.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
            check(written >= 0) { "音频输出失败：$written" }
            if (written == 0) {
                check(SystemClock.elapsedRealtime() < deadline) { "音频输出停滞" }
                Thread.sleep(5)
            } else {
                deadline = SystemClock.elapsedRealtime() + 5_000
            }
        }
        pcmFramesWritten += size / bytesPerFrame
    }

    private fun awaitPlayback() {
        val audio = track ?: return
        // Older Android versions require a full buffer to start a very short clip.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            writePcm(ByteBuffer.allocateDirect(audio.bufferSizeInFrames * bytesPerFrame))
        }
        val pending = (pcmFramesWritten - playbackHead(audio)).coerceAtLeast(0)
        val deadline = SystemClock.elapsedRealtime() + pending * 1_000 / audio.sampleRate + 3_000
        while (playbackHead(audio) < pcmFramesWritten) {
            check(SystemClock.elapsedRealtime() < deadline) { "等待音频播放完成超时" }
            Thread.sleep(10)
        }
    }

    private fun playbackHead(audio: AudioTrack) = audio.playbackHeadPosition.toLong() and 0xffffffffL

    private fun fail(error: Exception, fallback: String) {
        Log.e(TAG, fallback, error)
        sendStatus("error", error.message ?: fallback)
        release()
    }

    private fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.release() }
        track = null
        sessionId = null
        report = null
    }

    private fun sendStatus(state: String, error: String? = null) {
        val id = sessionId ?: return
        val status = org.json.JSONObject()
            .put("type", "tts")
            .put("state", state)
            .put("session_id", id)
            .put("frames_received", framesReceived)
            .put("frames_decoded", framesDecoded)
            .apply { error?.let { put("error", it) } }
        Log.i(TAG, status.toString())
        runCatching { report?.invoke(status) }
    }

    private fun nativeLong(value: Long): ByteBuffer = ByteBuffer.allocate(8)
        .order(ByteOrder.nativeOrder()).apply { putLong(value); flip() }

    private const val TAG = "RemoteTtsStreamPlayer"
    private const val SAMPLE_RATE = 16_000
    private const val FRAME_DURATION_US = 60_000L
    private val OPUS_HEAD = byteArrayOf(
        0x4f, 0x70, 0x75, 0x73, 0x48, 0x65, 0x61, 0x64, // OpusHead
        1, 1, 0, 0, 0x80.toByte(), 0x3e, 0, 0, 0, 0, 0,
    )
}
