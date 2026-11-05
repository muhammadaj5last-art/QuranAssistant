package com.example.quranassistant.data

import android.content.Context
import org.json.JSONArray

data class QuranSurah(
    val number: Int,
    val arabicName: String,
    val englishName: String
)

/** Reads the complete offline chapter catalog once per screen instance. */
class SurahCatalog(context: Context) {

    val surahs: List<QuranSurah>

    init {
        val json = context.applicationContext.assets
            .open("surahs.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        val array = JSONArray(json)
        surahs = List(array.length()) { index ->
            val entry = array.getJSONObject(index)
            QuranSurah(
                number = entry.getInt("number"),
                arabicName = entry.getString("arabicName"),
                englishName = entry.getString("englishName")
            )
        }

        require(surahs.size == 114 &&
            surahs.map { it.number }.sorted() == (1..114).toList()
        ) {
            "surahs.json must contain each Surah numbered 1 to 114 exactly once"
        }
    }
}
