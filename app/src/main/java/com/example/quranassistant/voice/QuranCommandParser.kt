package com.example.quranassistant.voice

import com.example.quranassistant.data.QuranSurah
import java.text.Normalizer
import java.util.Locale

enum class CommandAction {
    PLAY, PAUSE, RESUME, STOP, CONFIRM, UNKNOWN
}

data class ParsedQuranCommand(
    val action: CommandAction,
    val surahNumber: Int? = null,
    val surahEnglishName: String? = null,
    val surahArabicName: String? = null,
    val message: String
)

object QuranCommandParser {

    // These are speech-to-text variants, NOT independent playback commands.
    private val certainPlay = listOf(
        "شغل", "شغلي", "شغللي", "شغل لي", "اشغل",
        "تشغل", "تشغلي", "شغله", "شغلها", "سمعني", "اسمعني",
        "اقرا", "اقري", "اقرالي", "ابدأ", "ابدا",
        "اقراء","اقتراح","اقترح","يقرا","يقرأ","يقراء","أقترح","أقتراح",
        "شهر","سهر","شهري","شنو","شاغل","شاغ","شغ","الشاغل","الشاغ","شخيل","شاخل","الشاخل",
        "الشخل","","",
        "play", "start", "recite"
    )
    private val uncertainPlay = listOf(
        "شغال", "سحال", "شحال", "سغال", "مشغال", "مشغل"
    )

    private val stopWords = listOf(
        "اوقف", "وقف", "توقف", "ايقاف", "وقف التلاوه",
        "اوقف التلاوه", "اوقف القران", "stop", "stop playing", "end playback"
    )
    private val pauseWords = listOf(
        "وقف مؤقت", "اوقف مؤقت", "ايقاف مؤقت", "توقف مؤقت",
        "وقف شوي", "وقف شويه", "وقف لحظه", "اوقف لحظه",
        "pause", "hold"
    )
    private val temporaryWords = listOf(
        "مؤقت", "مؤقتا", "موقت", "موقتا", "شوي", "شويه"
    )
    private val resumeWords = listOf(
        "استأنف", "استناف", "كمل", "اكمل", "تابع", "واصل",
        "استمر", "resume", "continue", "unpause"
    )

    // “صورة” and even “صورتك” are common errors for “سورة”.
    // They are ONLY clues to a Surah name, not commands to play.
    private val surahMarkers = listOf(
        "سورة", "صورة", "سورت", "صورتك", "سورتك", "surah", "sura", "sora", "sorah",
        "ثورة","",""
    )

    fun parse(
        recognizedText: String,
        surahs: List<QuranSurah>
    ): ParsedQuranCommand {
        val text = normalize(recognizedText)
        if (text.isBlank()) return result(CommandAction.UNKNOWN, "No speech detected.")

        // Avoid acting on clearly negated instructions, even when the
        // command appears in the middle of a longer sentence.
        if (hasAny(text, listOf(
                "لا توقف", "ما توقف", "مو توقف", "لا تشغل", "ما تشغل",
                "لا شغل", "لا ابدا", "لا تبدا", "لا تكمل", "لا تستأنف",
                "dont stop", "don t stop", "dont play", "don t play",
                "do not stop", "do not play"
            ))) {
            return result(CommandAction.UNKNOWN, "Negated command. No action taken.")
        }

        // Playback controls do not need a Surah name.
        val stop = hasAny(text, stopWords)
        val pause = hasAny(text, pauseWords) ||
            (stop && hasAny(text, temporaryWords))
        val resume = hasAny(text, resumeWords)

        // Multiple different actions in one utterance need clarification;
        // do not guess which command the user intends to execute first.
        val containsPlayRequest = hasAny(text, certainPlay) || hasAny(text, uncertainPlay)
        if (resume && (pause || stop) ||
            (containsPlayRequest && hasAny(text, surahMarkers) && (pause || stop || resume))
        ) {
            return result(CommandAction.CONFIRM, "Conflicting playback commands. Please repeat.")
        }
        if (pause) return result(CommandAction.PAUSE, "PAUSE: Current recitation")
        if (stop) return result(CommandAction.STOP, "STOP: Current recitation")
        if (resume) return result(CommandAction.RESUME, "RESUME: Current recitation")

        val clearPlay = hasAny(text, certainPlay)
        val possiblePlay = hasAny(text, uncertainPlay)
        val hasMarker = hasAny(text, surahMarkers)

        // A speech model may output "بالفاتحة" instead of "الفاتحة".
        // Expand only prefixes that lead to a known Surah-name token.
        // The original text is also checked, so names are never lost.
        val surahText = expandSurahPrefixes(
            text = text,
            surahs = surahs,
            enabled = hasMarker || clearPlay || possiblePlay
        )

        // Exact Surah name matching is safe enough for automatic playback.
        val exactMatches = surahs.filter { surah ->
            aliasesFor(surah).any { alias ->
                val normalizedAlias = normalize(alias)
                val isVeryShortArabic = normalizedAlias.length <= 2 &&
                    normalizedAlias.any { it in '\u0621'..'\u064A' }

                (!isVeryShortArabic || hasMarker || clearPlay) &&
                    (containsPhrase(text, normalizedAlias) ||
                        containsPhrase(surahText, normalizedAlias))
            }
        }

        if (exactMatches.size > 1) {
            return result(CommandAction.CONFIRM, "More than one Surah detected. Please repeat.")
        }

        if (exactMatches.size == 1) {
            val surah = exactMatches.single()
            return when {
                clearPlay -> forSurah(CommandAction.PLAY, surah,
                    "PLAY: ${surah.englishName} (Surah ${surah.number})")
                possiblePlay -> forSurah(CommandAction.CONFIRM, surah,
                    "POSSIBLE PLAY: ${surah.englishName} (Surah ${surah.number}). Please repeat.")
                else -> forSurah(CommandAction.UNKNOWN, surah,
                    "Detected ${surah.englishName}, but no clear play command.")
            }
        }

        // Conservative typo recovery: at most one wrong character in a
        // *long* Arabic Surah name, only after a Surah marker and a play verb.
        // NEVER auto-play based on this fuzzy result.
        if (hasMarker && (clearPlay || possiblePlay)) {
            val fuzzyMatches = findNearSurahNames(surahText, surahs)
            if (fuzzyMatches.size == 1) {
                val surah = fuzzyMatches.single()
                return forSurah(CommandAction.CONFIRM, surah,
                    "POSSIBLE SURAH: ${surah.englishName} (Surah ${surah.number}). Please repeat.")
            }
            if (fuzzyMatches.size > 1) {
                return result(CommandAction.CONFIRM, "Surah name unclear. Please repeat.")
            }
        }

        return result(CommandAction.UNKNOWN, "Surah not recognized.")
    }

    // Handle attached Arabic prepositions/conjunctions in transcriptions.
    // Example: "شغل سورة بالفاتحة" -> "شغل سوره الفاتحه".
    // Only convert a token if the resulting token begins a catalogued
    // Surah name; avoid blindly stripping the first letter of every word.
    private fun expandSurahPrefixes(
        text: String,
        surahs: List<QuranSurah>,
        enabled: Boolean
    ): String {
        if (!enabled) return text

        val normalizedAliases = surahs.flatMap(::aliasesFor)
            .map(::normalize).toSet()
        val firstWords = normalizedAliases.map { it.substringBefore(' ') }.toSet()
        val words = text.split(' ').toMutableList()

        for (i in words.indices) {
            val word = words[i]
            val candidates = buildList {
                // بـ: بالفاتحة -> الفاتحة
                if (word.startsWith("ب") && word.length >= 3) {
                    add(word.drop(1))
                }
                // لـ + definite article: للرحمن -> الرحمن
                if (word.startsWith("لل") && word.length >= 4) {
                    add("ال" + word.drop(2))
                }
                // Conjunctions: والبقرة -> البقرة, فالفاتحة -> الفاتحة
                if ((word.startsWith("و") || word.startsWith("ف")) &&
                    word.length >= 4
                ) {
                    add(word.drop(1))
                }
            }

            val replacement = candidates.firstOrNull { candidate ->
                (candidate.length >= 3 && candidate in firstWords) ||
                    // Special case for the two-word name "آل عمران".
                    (candidate == "ال" &&
                        "ال ${words.getOrNull(i + 1)}" in normalizedAliases)
            }
            if (replacement != null) words[i] = replacement
        }
        return words.joinToString(" ")
    }

    private fun aliasesFor(surah: QuranSurah): List<String> {
        val names = mutableListOf(surah.arabicName, surah.englishName)
        val arabic = normalize(surah.arabicName)
        val english = normalize(surah.englishName)

        // E.g. “البقرة” / “بقرة”, “al baqarah” / “baqarah”.
        if (arabic.startsWith("ال") && arabic.length > 4) {
            names.add(arabic.removePrefix("ال"))
        }
        val englishWords = english.split(' ')
        if (englishWords.size > 1 && englishWords.first() in
            listOf("al", "an", "ar", "as", "at", "ash", "ad", "az", "adh")
        ) {
            names.add(englishWords.drop(1).joinToString(" "))
        }

        names.addAll(when (surah.number) {
            1 -> listOf("فاتحة", "fatiha", "fateha", "fatihah")
            2 -> listOf("بقرة", "baqara", "bakara", "bokhara", "al bokhara")
            3 -> listOf("عمران", "ali imran", "imran")
            20 -> listOf("طاها", "ta ha")
            36 -> listOf("ياسين", "ya seen", "yaseen")
            55 -> listOf("rahman", "rehman")
            112 -> listOf("اخلاص", "ikhlas", "ekhlas")
            else -> emptyList()
        })
        return names.distinct()
    }

    private fun findNearSurahNames(
        text: String,
        surahs: List<QuranSurah>
    ): List<QuranSurah> {
        val words = text.split(' ')
        val markerPositions = words.indices.filter { i ->
            words[i] in surahMarkers.map(::normalize)
        }
        val afterMarker = markerPositions.mapNotNull { i ->
            words.getOrNull(i + 1)
        }.filter { word ->
            word.length >= 5 && word.all { it in '\u0621'..'\u064A' }
        }

        if (afterMarker.isEmpty()) return emptyList()

        return surahs.filter { surah ->
            aliasesFor(surah).any { alias ->
                val a = normalize(alias)
                a.length >= 5 && a.all { it in '\u0621'..'\u064A' } &&
                    afterMarker.any { word -> oneEditApart(word, a) }
            }
        }
    }

    // Exactly one insertion, deletion or substitution. No broad fuzzy scoring.
    private fun oneEditApart(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
            } else {
                edits++
                if (edits > 1) return false
                when {
                    a.length > b.length -> i++
                    b.length > a.length -> j++
                    else -> { i++; j++ }
                }
            }
        }
        return edits + (a.length - i) + (b.length - j) == 1
    }

    private fun hasAny(text: String, phrases: List<String>): Boolean =
        phrases.any { containsPhrase(text, it) }

    private fun containsPhrase(text: String, phrase: String): Boolean {
        val normalized = normalize(phrase)
        return normalized.isNotEmpty() && " $text ".contains(" $normalized ")
    }

    private fun normalize(input: String): String {
        val withoutMarks = Normalizer.normalize(
            input.lowercase(Locale.ROOT), Normalizer.Form.NFD
        ).replace(Regex("\\p{M}+"), "")

        val simplified = withoutMarks.map { char ->
            when (char) {
                'ٱ', 'أ', 'إ', 'آ' -> 'ا'
                'ى', 'ئ' -> 'ي'
                'ة' -> 'ه'
                'ؤ' -> 'و'
                else -> char
            }
        }.joinToString("")

        return simplified
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun result(action: CommandAction, message: String) =
        ParsedQuranCommand(action = action, message = message)

    private fun forSurah(
        action: CommandAction, surah: QuranSurah, message: String
    ) = ParsedQuranCommand(
        action = action,
        surahNumber = surah.number,
        surahEnglishName = surah.englishName,
        surahArabicName = surah.arabicName,
        message = message
    )
}
