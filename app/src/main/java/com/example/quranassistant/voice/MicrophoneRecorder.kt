package com.example.quranassistant.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.sqrt

enum class RecordingEndReason {
    SILENCE_AFTER_SPEECH,
    NO_SPEECH_TIMEOUT,
    MAX_DURATION
}

data class SpeechRecording(
    val samples: FloatArray,
    val speechDetected: Boolean,
    val capturedDurationMs: Long,
    val endReason: RecordingEndReason
)

object MicrophoneRecorder {

    private const val SAMPLE_RATE = 16_000
    private const val FRAME_SAMPLES = 320 // 20 ms at 16 kHz

    // Starting values for the Galaxy S24 Ultra; adjust after real tests.
    private const val SPEECH_RMS_THRESHOLD = 0.010f
    private const val CONSECUTIVE_SPEECH_FRAMES = 5 // 100 ms
    private const val END_SILENCE_MS = 1_200
    private const val WAIT_FOR_SPEECH_MS = 4_500
    private const val MAX_RECORDING_MS = 12_000
    private const val LEADING_PADDING_MS = 300

    /**
     * Stops when the user has been silent for ~1.2 s after speech,
     * or when no speech is detected within 4.5 s.
     * Always stops by 12 s. Call from Dispatchers.IO.
     *
     * This is an energy-based prototype, not a trained VAD model.
     */
    @SuppressLint("MissingPermission")
    fun recordUntilSilence(): SpeechRecording {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBufferBytes > 0) {
            "16 kHz mono audio recording is unavailable"
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferBytes, 8_192)
        )

        val maxSamples = SAMPLE_RATE * MAX_RECORDING_MS / 1_000
        val recorded = FloatArray(maxSamples)
        val frame = ShortArray(FRAME_SAMPLES)
        var totalSamples = 0
        var consecutiveLoudFrames = 0
        var candidateSpeechStart = 0
        var actualSpeechStart = 0
        var lastLoudSample = 0
        var speechDetected = false
        var endReason = RecordingEndReason.MAX_DURATION

        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "Unable to initialize microphone"
            }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Microphone did not start recording"
            }

            while (totalSamples < maxSamples) {
                val request = minOf(frame.size, maxSamples - totalSamples)
                val count = recorder.read(
                    frame,
                    0,
                    request,
                    AudioRecord.READ_BLOCKING
                )
                check(count > 0) {
                    "Microphone read failed (code $count)"
                }

                var sumSquares = 0.0
                for (i in 0 until count) {
                    val sample = frame[i].toFloat() / 32_768f
                    recorded[totalSamples + i] = sample
                    sumSquares += sample.toDouble() * sample
                }

                val rms = sqrt(sumSquares / count).toFloat()
                val frameStartSample = totalSamples
                totalSamples += count

                if (rms >= SPEECH_RMS_THRESHOLD) {
                    if (consecutiveLoudFrames == 0) {
                        candidateSpeechStart = frameStartSample
                    }
                    consecutiveLoudFrames++
                    lastLoudSample = totalSamples

                    if (!speechDetected &&
                        consecutiveLoudFrames >= CONSECUTIVE_SPEECH_FRAMES
                    ) {
                        speechDetected = true
                        actualSpeechStart = candidateSpeechStart
                    }
                } else {
                    consecutiveLoudFrames = 0
                }

                val elapsedMs = totalSamples * 1_000L / SAMPLE_RATE

                if (speechDetected) {
                    val quietMs = (totalSamples - lastLoudSample) * 1_000L / SAMPLE_RATE
                    if (quietMs >= END_SILENCE_MS) {
                        endReason = RecordingEndReason.SILENCE_AFTER_SPEECH
                        break
                    }
                } else if (elapsedMs >= WAIT_FOR_SPEECH_MS) {
                    endReason = RecordingEndReason.NO_SPEECH_TIMEOUT
                    break
                }
            }

            val durationMs = totalSamples * 1_000L / SAMPLE_RATE
            if (!speechDetected) {
                return SpeechRecording(
                    samples = floatArrayOf(),
                    speechDetected = false,
                    capturedDurationMs = durationMs,
                    endReason = endReason
                )
            }

            // Keep 300 ms of audio before speech starts, so the first word
            // is less likely to get cut off. Remove excessive leading silence.
            val startSample = maxOf(
                0,
                actualSpeechStart - SAMPLE_RATE * LEADING_PADDING_MS / 1_000
            )

            return SpeechRecording(
                samples = recorded.copyOfRange(startSample, totalSamples),
                speechDetected = true,
                capturedDurationMs = durationMs,
                endReason = endReason
            )
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { recorder.stop() }
            }
            recorder.release()
        }
    }

    /** Retained for comparison and easy rollback while testing. */
    @SuppressLint("MissingPermission")
    fun recordFiveSeconds(): FloatArray {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBufferBytes > 0) { "16 kHz mono recording is unavailable" }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferBytes, 8_192)
        )

        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "Unable to initialize microphone"
            }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Microphone did not start recording"
            }

            val samples = FloatArray(SAMPLE_RATE * 5)
            val buffer = ShortArray(2_048)
            var total = 0
            while (total < samples.size) {
                val count = recorder.read(
                    buffer,
                    0,
                    minOf(buffer.size, samples.size - total),
                    AudioRecord.READ_BLOCKING
                )
                check(count > 0) { "Microphone read failed (code $count)" }
                for (i in 0 until count) {
                    samples[total + i] = buffer[i].toFloat() / 32_768f
                }
                total += count
            }
            return samples
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { recorder.stop() }
            }
            recorder.release()
        }
    }
}
