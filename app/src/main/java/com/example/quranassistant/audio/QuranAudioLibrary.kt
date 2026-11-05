package com.example.quranassistant.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class AudioImportReport(
    val imported: List<Int>,
    val skipped: List<String>,
    val failed: List<String>
)

class QuranAudioLibrary(context: Context) {

    private val appContext = context.applicationContext
    private val audioDirectory = File(appContext.filesDir, "quran_audio")

    // Examples: 001.mp3, 1.mp3, 055.mp3, surah_055.mp3
    private val filenamePattern = Regex(
        "^(?:surah[_ -]?)?0*([0-9]{1,3})\\.mp3$",
        RegexOption.IGNORE_CASE
    )

    fun importedFile(surahNumber: Int): File? {
        if (surahNumber !in 1..114) return null

        val file = File(
            audioDirectory,
            "${surahNumber.toString().padStart(3, '0')}.mp3"
        )

        return file.takeIf { it.isFile && it.length() > 0L }
    }

    fun hasAudio(surahNumber: Int): Boolean {
        if (surahNumber !in 1..114) return false
        return importedFile(surahNumber) != null ||
            surahNumber in setOf(1, 2, 112)
    }

    // Delete only the app-private imported copy. Never delete the original
    // user-selected download or the bundled res/raw recording.
    fun removeImportedFile(surahNumber: Int): Boolean {
        val file = importedFile(surahNumber) ?: return false
        return file.delete()
    }

    // Call this on Dispatchers.IO, not the Android main thread.
    fun importMp3Files(uris: List<Uri>): AudioImportReport {
        check(audioDirectory.isDirectory || audioDirectory.mkdirs()) {
            "Could not create the audio directory"
        }

        val imported = mutableListOf<Int>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for (uri in uris) {
            val name = getDisplayName(uri)
            val surahNumber = filenamePattern.matchEntire(name)
                ?.groupValues?.get(1)?.toIntOrNull()

            if (surahNumber == null || surahNumber !in 1..114) {
                skipped.add(name)
                continue
            }

            val filename = "${surahNumber.toString().padStart(3, '0')}.mp3"
            val destination = File(audioDirectory, filename)
            val temporaryFile = File(audioDirectory, ".$filename.part")

            try {
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: error("Cannot open $name")

                input.use { source ->
                    temporaryFile.outputStream().use { output ->
                        source.copyTo(output)
                    }
                }

                check(temporaryFile.length() > 0L) {
                    "The selected audio file is empty"
                }

                Files.move(
                    temporaryFile.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )

                imported.add(surahNumber)

            } catch (e: Exception) {
                temporaryFile.delete()
                failed.add("$name: ${e.message ?: "Import failed"}")
            }
        }

        return AudioImportReport(
            imported = imported,
            skipped = skipped,
            failed = failed
        )
    }

    private fun getDisplayName(uri: Uri): String {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)

        val name = appContext.contentResolver.query(
            uri, projection, null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

        return name ?: "unknown.mp3"
    }
}
