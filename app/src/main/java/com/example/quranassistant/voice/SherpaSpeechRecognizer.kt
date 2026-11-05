
package com.example.quranassistant.voice

import android.content.Context

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig

enum class SpeechLanguage {
    ARABIC,
    ENGLISH
}

class SherpaSpeechRecognizer(
    context: Context,
    language: SpeechLanguage = SpeechLanguage.ARABIC
) : AutoCloseable {

    private val recognizer: OfflineRecognizer

    init {
        val modelDir = when (language) {
            SpeechLanguage.ARABIC -> "models/ar"
            SpeechLanguage.ENGLISH -> "models/en"
        }

        val modelConfig = OfflineModelConfig(
            moonshine = OfflineMoonshineModelConfig(
                encoder = "$modelDir/encoder_model.ort",
                mergedDecoder = "$modelDir/decoder_model_merged.ort"
            ),
            tokens = "$modelDir/tokens.txt",
            numThreads = 4,
            provider = "cpu"
        )

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = 16000,
                featureDim = 80
            ),
            modelConfig = modelConfig
        )

        recognizer = OfflineRecognizer(
            assetManager = context.assets,
            config = config
        )
    }

    fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()

        return try {
            stream.acceptWaveform(
                samples,
                sampleRate = 16000
            )

            recognizer.decode(stream)
            recognizer.getResult(stream).text

        } finally {
            stream.release()
        }
    }

    override fun close() {
        recognizer.release()
    }
}
