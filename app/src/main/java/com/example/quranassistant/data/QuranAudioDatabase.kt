package com.example.quranassistant.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.quranassistant.audio.QuranAudioLibrary
import java.io.File

/**
 * Stores the 114 Surah names and information about MP3 files saved on this phone.
 * The MP3 bytes are stored in files, never in SQLite BLOB columns.
 */
class QuranAudioDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "quran_audio.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE surahs (
                number INTEGER PRIMARY KEY,
                arabic_name TEXT NOT NULL,
                english_name TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE recordings (
                surah_number INTEGER PRIMARY KEY,
                reciter TEXT NOT NULL,
                local_path TEXT NOT NULL,
                source_url TEXT,
                source_type TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                saved_at_ms INTEGER NOT NULL,
                FOREIGN KEY(surah_number) REFERENCES surahs(number)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 has no migrations yet. When changing this schema,
        // add a migration instead of deleting the user's saved records.
        if (oldVersion != newVersion) {
            throw IllegalStateException("A SQLite migration is required")
        }
    }

    /** Seed/update all 114 entries from the existing, verified surahs.json catalog. */
    fun seedSurahs(surahs: List<QuranSurah>) {
        require(surahs.size == 114 && surahs.map { it.number }.toSet() == (1..114).toSet()) {
            "Expected exactly 114 distinct Surahs"
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (surah in surahs) {
                val values = ContentValues().apply {
                    put("number", surah.number)
                    put("arabic_name", surah.arabicName)
                    put("english_name", surah.englishName)
                }
                // IGNORE preserves foreign-key-linked recordings rows.
                db.insertWithOnConflict("surahs", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                db.update(
                    "surahs", values,
                    "number = ?", arrayOf(surah.number.toString())
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Keep existing manual imports searchable through SQLite as well. */
    fun indexExistingImports(library: QuranAudioLibrary) {
        for (number in 1..114) {
            val file = library.importedFile(number) ?: continue
            if (!hasRecording(number)) {
                saveRecording(number, file, "imported", null)
            }
        }
    }

    fun hasRecording(number: Int): Boolean {
        readableDatabase.rawQuery(
            "SELECT local_path FROM recordings WHERE surah_number = ?",
            arrayOf(number.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return false
            val path = cursor.getString(0)
            return File(path).isFile && File(path).length() > 0L
        }
    }

    fun saveRecording(number: Int, file: File, sourceType: String, sourceUrl: String?) {
        require(number in 1..114)
        require(file.isFile && file.length() > 0L)
        val values = ContentValues().apply {
            put("surah_number", number)
            put("reciter", "Mishary Rashid Alafasy")
            put("local_path", file.absolutePath)
            put("source_url", sourceUrl)
            put("source_type", sourceType)
            put("size_bytes", file.length())
            put("saved_at_ms", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "recordings", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** Count only MP3 files actually present, not merely database rows. */
    fun savedFileCount(): Int {
        var count = 0
        readableDatabase.rawQuery("SELECT local_path FROM recordings", null).use { cursor ->
            while (cursor.moveToNext()) {
                val file = File(cursor.getString(0))
                if (file.isFile && file.length() > 0L) count++
            }
        }
        return count
    }
}
