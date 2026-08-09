package com.homeworkbuddy

import android.media.MediaActionSound

/** Uses Android's standard shutter sound for visible remote camera actions. */
object CameraShutterSound {
    private val sound = MediaActionSound()

    fun play() = sound.play(MediaActionSound.SHUTTER_CLICK)
}
