package com.example.quranassistant.voice

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Short, distinct confirmation sounds using Android's built-in tone API.
 * No MP3/WAV assets or permissions are required. Uses media volume.
 */
class AssistantFeedbackSounds : AutoCloseable {
    private val tones: ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, 65)
    }.getOrNull()

    fun wake() = play(ToneGenerator.TONE_PROP_ACK, 320)
    fun commandAccepted() = play(ToneGenerator.TONE_PROP_PROMPT, 190)
    fun sleep() = play(ToneGenerator.TONE_PROP_BEEP2, 330)
    fun uncertain() = play(ToneGenerator.TONE_PROP_NACK, 200)

    private fun play(tone: Int, durationMs: Int) {
        runCatching { tones?.startTone(tone, durationMs) }
    }

    override fun close() {
        runCatching { tones?.release() }
    }
}
