package com.homeworkbuddy

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Bundled speech, one reason at a time; all operations run on the UI thread. */
internal class HeadPoseReminderSound(private val context: Context, private val prompts: List<HeadPoseVoicePrompt>) : AutoCloseable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var paused = false
    private var index = 0
    private val next = Runnable { if (!closed && !paused) playNext() }
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            if (closed) return@setOnAudioFocusChangeListener
            runCatching {
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> close()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { paused = true; handler.removeCallbacks(next); player?.pause() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(.2f, .2f)
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        paused = false
                        handler.removeCallbacks(next)
                        if (player != null) { player?.setVolume(1f, 1f); player?.start() }
                        else handler.post(next)
                    }
                }
            }.onFailure { close() }
        }, Handler(Looper.getMainLooper()))
        .build()

    init {
        try {
            require(prompts.isNotEmpty())
            if (manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) playNext()
            else close()
        } catch (error: Exception) {
            Log.w("HeadPoseReminder", "Unable to play reminder", error)
            close()
        }
    }

    private fun playNext() {
        if (closed || paused || player != null) return
        try {
            val resource = when (prompts[index]) {
                HeadPoseVoicePrompt.HEAD_HIGH -> R.raw.pose_head_high
                HeadPoseVoicePrompt.HEAD_LOW -> R.raw.pose_head_low
                HeadPoseVoicePrompt.TURN_BACK -> R.raw.pose_turn_back
                HeadPoseVoicePrompt.TOO_CLOSE -> R.raw.pose_too_close
                HeadPoseVoicePrompt.TOO_FAR -> R.raw.pose_too_far
            }
            val media = checkNotNull(MediaPlayer.create(context, resource, attributes, 0))
            player = media
            media.setOnErrorListener { _, what, extra ->
                Log.w("HeadPoseReminder", "Playback error $what/$extra")
                close()
                true
            }
            media.setOnCompletionListener { finished ->
                if (player === finished) {
                    player = null
                    finished.release()
                    index = (index + 1) % prompts.size
                    handler.postDelayed(next, 8_000)
                }
            }
            media.start()
        } catch (error: Exception) {
            Log.w("HeadPoseReminder", "Unable to play speech", error)
            close()
        }
    }

    override fun close() {
        closed = true
        handler.removeCallbacks(next)
        val old = player
        player = null
        runCatching { old?.release() }
        runCatching { manager.abandonAudioFocusRequest(focus) }
    }
}
