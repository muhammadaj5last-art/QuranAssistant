package com.example.quranassistant.voice

import java.text.Normalizer
import java.util.Locale

/**
 * A wake phrase may occur inside a longer transcription:
 *   "السلام عليكم يا مساعد القرآن لو سمحت".
 * We require BOTH "مساعد" and "قرآن" rather than matching either alone.
 */
object ArabicWakePhraseMatcher {

    data class WakeMatch(
        val detected: Boolean,
        val textAfterWake: String = ""
    )

    // Arabic letters are normalized before matching. The word boundaries
    // prevent activating from fragments inside other Arabic words.
    private val wakePattern = Regex(
        "(?<![\\p{L}\\p{N}])(?:يا\\s+)?(?:مساعد|المساعد)\\s+(?:القران|للقران|قران)(?![\\p{L}\\p{N}])"
    )

    fun match(recognizedText: String): WakeMatch {
        val text = normalize(recognizedText)
        val hit = wakePattern.find(text) ?: return WakeMatch(false)
        return WakeMatch(
            detected = true,
            textAfterWake = text.substring(hit.range.last + 1).trim()
        )
    }

    fun isWakePhrase(recognizedText: String): Boolean = match(recognizedText).detected

    private fun normalize(input: String): String {
        val withoutMarks = Normalizer.normalize(
            input.lowercase(Locale.ROOT), Normalizer.Form.NFD
        ).replace(Regex("\\p{M}+"), "")
        return withoutMarks.map { char ->
            when (char) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى', 'ئ' -> 'ي'
                'ة' -> 'ه'
                'ؤ' -> 'و'
                else -> char
            }
        }.joinToString("")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
