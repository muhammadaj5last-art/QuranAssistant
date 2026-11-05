
package com.example.quranassistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.example.quranassistant.R
import java.io.FileInputStream

class QuranAudioPlayer(
    context: Context
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val library = QuranAudioLibrary(appContext)

    private var mediaPlayer: MediaPlayer? = null

    var onPlaybackCompleted: (() -> Unit)? = null

    val hasRecitation: Boolean
        get() = mediaPlayer != null

    val isPlaying: Boolean
        get() = mediaPlayer?.isPlaying == true

    fun playSurah(surahNumber: Int) {

        require(surahNumber in 1..114) {
            "Invalid Surah number: $surahNumber"
        }

        // Imported audio takes priority over bundled audio.
        val importedAudio = library.importedFile(surahNumber)

        val bundledResource = when (surahNumber) {
            1 -> R.raw.surah_001
            2 -> R.raw.surah_002
            112 -> R.raw.surah_112
            else -> null
        }

        if (importedAudio == null &&
            bundledResource == null
        ) {
            throw IllegalArgumentException(
                "Audio not installed for Surah $surahNumber"
            )
        }

        stop()

        val player = if (importedAudio != null) {

            MediaPlayer().apply {

                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            AudioAttributes.USAGE_MEDIA
                        )
                        .setContentType(
                            AudioAttributes.CONTENT_TYPE_MUSIC
                        )
                        .build()
                )

                try {
                    FileInputStream(importedAudio).use { input ->
                        setDataSource(input.fd)
                        prepare()
                    }
                } catch (e: Exception) {
                    release()
                    throw e
                }
            }

        } else {

            MediaPlayer.create(
                appContext,
                bundledResource!!
            ) ?: throw IllegalStateException(
                "Could not open Surah $surahNumber"
            )
        }

        mediaPlayer = player

        player.setOnCompletionListener { completedPlayer ->
            if (mediaPlayer === completedPlayer) {
                stop()
                onPlaybackCompleted?.invoke()
            }
        }

        try {
            player.start()
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    fun pause(): Boolean {
        val player = mediaPlayer ?: return false

        if (!player.isPlaying) return false

        player.pause()
        return true
    }

    fun resume(): Boolean {
        val player = mediaPlayer ?: return false

        if (player.isPlaying) return false

        player.start()
        return true
    }

    fun stop(): Boolean {
        val player = mediaPlayer ?: return false

        mediaPlayer = null
        player.setOnCompletionListener(null)
        player.release()

        return true
    }

    override fun close() {
        stop()
        onPlaybackCompleted = null
    }
}
