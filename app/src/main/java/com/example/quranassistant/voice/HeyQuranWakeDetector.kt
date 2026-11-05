package com.example.quranassistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlin.math.abs

data class WakeDiagnostics(
    val samplesReceived: Long,
    val peakAudioLevel: Float,
    val decodeCalls: Long
)

/** Foreground-only keyword spotter. Never call from the UI thread. */
class HeyQuranWakeDetector(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var shouldStop = false

    @Volatile
    private var activeRecorder: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun listenUntilDetected(
        onDiagnostics: (WakeDiagnostics) -> Unit = {}
    ): String? {
        shouldStop = false

        val path = "models/kws"
        val suffix = "epoch-12-avg-2-chunk-16-left-64.int8.onnx"

        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$path/encoder-$suffix",
                    decoder = "$path/decoder-$suffix",
                    joiner = "$path/joiner-$suffix"
                ),
                tokens = "$path/tokens.txt",
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer2"
            ),
            keywordsFile = "$path/keywords.txt",
            maxActivePaths = 4,
            numTrailingBlanks = 1,
            keywordsScore = 1.0f,
            keywordsThreshold = 0.25f
        )

        val spotter = KeywordSpotter(
            assetManager = appContext.assets,
            config = config
        )

        var recorder: AudioRecord? = null
        var stream: OnlineStream? = null

        try {
            stream = spotter.createStream()

            val minBufferBytes = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minBufferBytes > 0) { "16 kHz microphone unavailable" }

            // Match the Android keyword-spotting example's MIC source.
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBufferBytes * 2, 6400)
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "Microphone failed to initialize"
            }

            activeRecorder = recorder
            if (shouldStop) return null

            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Microphone recording did not start"
            }

            val pcm = ShortArray(1600) // 100 ms at 16 kHz
            var totalSamples = 0L
            var decodeCount = 0L
            var peak = 0f
            var lastReportAt = SystemClock.elapsedRealtime()

            onDiagnostics(WakeDiagnostics(0L, 0f, 0L))

            while (!shouldStop) {
                val count = recorder.read(
                    pcm, 0, pcm.size, AudioRecord.READ_BLOCKING
                )

                if (count <= 0) {
                    if (shouldStop) break
                    if (count < 0) error("Microphone read error: $count")
                    continue
                }

                val samples = FloatArray(count) { index ->
                    (pcm[index] / 32768f).also {
                        peak = maxOf(peak, abs(it))
                    }
                }
                totalSamples += count
                stream.acceptWaveform(samples, sampleRate = 16000)

                while (!shouldStop && spotter.isReady(stream)) {
                    spotter.decode(stream)
                    decodeCount++

                    val keyword = spotter.getResult(stream).keyword
                    if (keyword.isNotBlank()) {
                        spotter.reset(stream)
                        onDiagnostics(
                            WakeDiagnostics(totalSamples, peak, decodeCount)
                        )
                        return keyword
                    }
                }

                val now = SystemClock.elapsedRealtime()
                if (now - lastReportAt >= 1000L) {
                    onDiagnostics(
                        WakeDiagnostics(totalSamples, peak, decodeCount)
                    )
                    lastReportAt = now
                }
            }
            return null
        } finally {
            activeRecorder = null
            recorder?.let { r ->
                if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    runCatching { r.stop() }
                }
                r.release()
            }
            stream?.release()
            spotter.release()
        }
    }

    fun stop() {
        shouldStop = true
        activeRecorder?.let { recorder ->
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { recorder.stop() }
            }
        }
    }
}
