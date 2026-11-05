package com.example.quranassistant.audio

import android.content.Context
import com.example.quranassistant.data.QuranAudioDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Downloads one full-surah Mishary Alafasy MP3 into the SAME directory used by
 * QuranAudioLibrary/QuranAudioPlayer. Call from a coroutine, never the UI thread.
 *
 * MP3Quran currently provides the Alafasy Hafs collection under this host.
 * If the provider changes the address, replace only BASE_URL.
 */
class AlafasyAudioDownloader(
    context: Context,
    private val database: QuranAudioDatabase
) {
    private val appContext = context.applicationContext
    private val library = QuranAudioLibrary(appContext)
    private val directory = File(appContext.filesDir, "quran_audio")

    companion object {
        private const val BASE_URL = "https://server8.mp3quran.net/afs/"
        private const val MAX_FILE_BYTES = 500L * 1024L * 1024L
    }

    suspend fun ensureAvailable(
        surahNumber: Int,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        require(surahNumber in 1..114)
        if (library.hasAudio(surahNumber)) return@withContext false

        check(directory.isDirectory || directory.mkdirs()) {
            "Could not create Quran audio directory"
        }

        val filename = "${surahNumber.toString().padStart(3, '0')}.mp3"
        val url = BASE_URL + filename
        val destination = File(directory, filename)
        val temp = File(directory, ".$filename.download")
        var connection: HttpURLConnection? = null

        // If the user leaves the screen or cancels the coroutine, this file
        // is deleted and the existing recordings are untouched.
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "audio/mpeg, audio/*, application/octet-stream")
                connect()
            }

            val status = connection.responseCode
            check(status == HttpURLConnection.HTTP_OK) {
                "Audio server returned HTTP $status for Surah $surahNumber"
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            if (totalBytes != null) {
                check(totalBytes <= MAX_FILE_BYTES) { "Audio file is unexpectedly large" }
                check(directory.usableSpace > totalBytes + 5L * 1024L * 1024L) {
                    "Not enough free storage to download this Surah"
                }
            }

            val contentType = connection.contentType?.lowercase().orEmpty()
            check(!contentType.contains("text/html") && !contentType.contains("application/json")) {
                "Server returned a web page instead of audio: $contentType"
            }

            var copied = 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n == -1) break
                        output.write(buffer, 0, n)
                        copied += n
                        check(copied <= MAX_FILE_BYTES) { "Audio exceeded allowed size" }
                        if (copied - lastReport >= 512 * 1024) {
                            onProgress(copied, totalBytes)
                            lastReport = copied
                        }
                    }
                }
            }
            check(copied >= 2048L) { "The audio download is empty or incomplete" }
            if (totalBytes != null) {
                check(copied == totalBytes) { "Downloaded file size did not match the server" }
            }
            check(looksLikeMp3(temp)) { "Downloaded content is not an MP3 file" }
            currentCoroutineContext().ensureActive()

            Files.move(
                temp.toPath(), destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
            database.saveRecording(surahNumber, destination, "downloaded", url)
            onProgress(copied, totalBytes)
            true
        } finally {
            connection?.disconnect()
            if (temp.exists()) temp.delete()
        }
    }

    private fun looksLikeMp3(file: File): Boolean {
        val header = ByteArray(3)
        file.inputStream().use { input ->
            if (input.read(header) != header.size) return false
        }
        val id3 = header[0] == 'I'.code.toByte() &&
            header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()
        val first = header[0].toInt() and 0xff
        val second = header[1].toInt() and 0xff
        val mpegFrame = first == 0xff && (second and 0xe0) == 0xe0
        return id3 || mpegFrame
    }
}
