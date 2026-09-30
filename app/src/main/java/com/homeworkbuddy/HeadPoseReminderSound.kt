package com.homeworkbuddy

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log

/** One looping phrase per alert episode; all calls and focus callbacks run on the UI thread. */
internal class HeadPoseReminderSound(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var player: MediaPlayer? = null
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            runCatching {
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> close()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player?.pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(.2f, .2f)
                    AudioManager.AUDIOFOCUS_GAIN -> { player?.setVolume(1f, 1f); player?.start() }
                }
            }.onFailure { close() }
        }, Handler(Looper.getMainLooper()))
        .build()

    init {
        try {
            player = checkNotNull(MediaPlayer.create(context, R.raw.pose_reminder, attributes, 0))
            player?.setOnErrorListener { _, what, extra ->
                Log.w("HeadPoseReminder", "Playback error $what/$extra")
                close()
                true
            }
            player?.isLooping = true
            if (manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) player?.start()
            else close()
        } catch (error: Exception) {
            Log.w("HeadPoseReminder", "Unable to play reminder", error)
            close()
        }
    }

    override fun close() {
        val old = player
        player = null
        runCatching { old?.release() }
        runCatching { manager.abandonAudioFocusRequest(focus) }
    }
}
