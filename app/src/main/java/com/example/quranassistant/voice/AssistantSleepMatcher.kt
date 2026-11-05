package com.example.quranassistant.voice

import java.text.Normalizer
import java.util.Locale

/** Sleep the voice assistant, NOT the Quran player. */
object AssistantSleepMatcher {
    private val shortSleepWords = setOf(
        "اطفي", "طفي", "اطف", "اطفا", "اطفئ"
    )
    private val explicitPhrases = setOf(
        "قفل المساعد", "اقفل المساعد", "اغلق المساعد",
        "وقف الاستماع", "اوقف الاستماع", "توقف عن الاستماع",
        "نام يا مساعد", "يا مساعد نام",
        "stop listening", "go to sleep", "sleep assistant"
    )
    private val negations = setOf("لا", "مو", "مش", "مات", "not", "dont")

    fun isSleepCommand(recognizedText: String): Boolean {
        val text = normalize(recognizedText)
        if (text.isBlank()) return false
        val words = text.split(' ')

        // A sleep word can occur anywhere in a longer utterance, except
        // directly after a simple negation: "لا تطفي" is not a sleep command.
        for (i in words.indices) {
            if (words[i] in shortSleepWords && (i == 0 || words[i - 1] !in negations)) {
                return true
            }
        }
        return explicitPhrases.any { phrase ->
            val p = normalize(phrase)
            val index = words.windowed(p.split(' ').size).indexOfFirst { it.joinToString(" ") == p }
            index >= 0 && (index == 0 || words[index - 1] !in negations)
        }
    }

    private fun normalize(input: String): String {
        val unmarked = Normalizer.normalize(
            input.lowercase(Locale.ROOT), Normalizer.Form.NFD
        ).replace(Regex("\\p{M}+"), "")
        return unmarked.map { c ->
            when (c) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى', 'ئ' -> 'ي'
                'ة' -> 'ه'
                'ؤ' -> 'و'
                else -> c
            }
        }.joinToString("")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
