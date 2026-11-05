package com.example.quranassistant.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

/**
 * A cancellable, energy-based recorder for the foreground hands-free prototype.
 * This deliberately does not modify the working MicrophoneRecorder class.
 * The microphone is opened for one short speech segment and released each time.
 */
class HandsFreeSegmentRecorder {

    @Volatile private var stopRequested = false
    @Volatile private var interruptRequested = false

    fun resetSession() {
        stopRequested = false
        interruptRequested = false
    }

    /** Stop all future recording and interrupt the current short read loop. */
    fun requestStop() {
        stopRequested = true
        interruptRequested = true
    }

    /** Interrupt capture when Quran playback starts, but keep the session alive. */
    fun interruptForPlayback() {
        interruptRequested = true
    }

    val isStopRequested: Boolean
        get() = stopRequested

    @SuppressLint("MissingPermission") // The screen checks RECORD_AUDIO first.
    fun capture(): SpeechRecording {
        if (stopRequested) return emptyRecording()
        interruptRequested = false

        val sampleRate = 16_000
        val frameSamples = 320        // 20 milliseconds
        val maxDurationMs = 10_000
        val waitForSpeechMs = 4_500
        val endSilenceMs = 1_200
        val leadingPaddingMs = 300
        val speechRmsThreshold = 0.010f
        val minConsecutiveFrames = 5

        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBufferBytes > 0) { "16 kHz mono recording unavailable" }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferBytes, 8_192)
        )

        val recorded = FloatArray(sampleRate * maxDurationMs / 1_000)
        val frame = ShortArray(frameSamples)
        var total = 0
        var loudFrames = 0
        var candidateStart = 0
        var speechStart = 0
        var lastLoud = 0
        var speechDetected = false
        var reason = RecordingEndReason.MAX_DURATION

        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "Unable to initialize microphone"
            }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Microphone did not start recording"
            }

            while (total < recorded.size && !stopRequested && !interruptRequested) {
                val requested = minOf(frame.size, recorded.size - total)
                val count = recorder.read(
                    frame, 0, requested, AudioRecord.READ_BLOCKING
                )
                if (count <= 0) {
                    if (stopRequested || interruptRequested) break
                    error("Microphone read failed: $count")
                }

                val frameStart = total
                var energy = 0.0
                for (i in 0 until count) {
                    val sample = frame[i].toFloat() / 32_768f
                    recorded[total + i] = sample
                    energy += sample.toDouble() * sample
                }
                total += count

                val rms = sqrt(energy / count).toFloat()
                if (rms >= speechRmsThreshold) {
                    if (loudFrames == 0) candidateStart = frameStart
                    loudFrames++
                    lastLoud = total
                    if (!speechDetected && loudFrames >= minConsecutiveFrames) {
                        speechDetected = true
                        speechStart = candidateStart
                    }
                } else {
                    loudFrames = 0
                }

                val elapsedMs = total * 1_000L / sampleRate
                if (speechDetected) {
                    val silenceMs = (total - lastLoud) * 1_000L / sampleRate
                    if (silenceMs >= endSilenceMs) {
                        reason = RecordingEndReason.SILENCE_AFTER_SPEECH
                        break
                    }
                } else if (elapsedMs >= waitForSpeechMs) {
                    reason = RecordingEndReason.NO_SPEECH_TIMEOUT
                    break
                }
            }

            val durationMs = total * 1_000L / sampleRate
            if (stopRequested || interruptRequested || !speechDetected) {
                return emptyRecording(durationMs)
            }

            val start = maxOf(
                0, speechStart - sampleRate * leadingPaddingMs / 1_000
            )
            return SpeechRecording(
                samples = recorded.copyOfRange(start, total),
                speechDetected = true,
                capturedDurationMs = durationMs,
                endReason = reason
            )
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { recorder.stop() }
            }
            recorder.release()
        }
    }

    private fun emptyRecording(durationMs: Long = 0L) = SpeechRecording(
        samples = floatArrayOf(),
        speechDetected = false,
        capturedDurationMs = durationMs,
        endReason = RecordingEndReason.NO_SPEECH_TIMEOUT
    )
}
