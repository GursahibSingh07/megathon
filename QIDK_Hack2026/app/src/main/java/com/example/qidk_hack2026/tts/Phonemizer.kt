package com.example.qidk_hack2026.tts

import android.content.Context

/**
 * English text -> espeak-style IPA, which is what Piper voices were trained on.
 * Words come from CMUdict (ARPAbet -> IPA); unknown words are spelled out letter by letter.
 */
class Phonemizer(context: Context) {
    private val dict: Map<String, List<String>> = context.assets.open("tts/cmudict.txt")
        .bufferedReader().useLines { lines ->
            lines.associate { l ->
                val i = l.indexOf(' ')
                l.substring(0, i) to l.substring(i + 1).split(' ')
            }
        }

    fun phonemize(text: String): String {
        val sb = StringBuilder()
        TOKEN.findAll(expandNumbers(text)).forEach { m ->
            val t = m.value
            when {
                t[0].isLetter() || t[0] == '\'' -> {
                    if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                    sb.append(word(t.lowercase()))
                }
                t[0] in ".,!?;:" -> sb.append(t[0])
                t[0] == '-' || t[0] == '—' -> sb.append(',')
            }
        }
        return sb.toString().trim()
    }

    private fun word(w: String): String {
        dict[w]?.let { return arpaToIpa(it) }
        if (w.endsWith("'s")) dict[w.dropLast(2)]?.let { return arpaToIpa(it) + "z" }
        // Unknown word: spell it out.
        return w.filter { it.isLetter() }.map { dict[it.toString()]?.let(::arpaToIpa) ?: "" }.joinToString(" ")
    }

    private fun arpaToIpa(phones: List<String>): String {
        val out = StringBuilder()
        var onset = out.length // where the current syllable's consonants began
        for (p in phones) {
            val stress = p.lastOrNull()?.takeIf { it.isDigit() }
            val base = p.trimEnd('0', '1', '2')
            val vowel = stress != null
            if (vowel) {
                val mark = when (stress) { '1' -> "ˈ"; '2' -> "ˌ"; else -> "" }
                out.insert(onset, mark)
                out.append(vowelIpa(base, stress!!))
                onset = out.length
            } else {
                out.append(CONSONANTS[base] ?: "")
            }
        }
        return out.toString()
    }

    private fun vowelIpa(base: String, stress: Char): String = when (base) {
        "AH" -> if (stress == '0') "ə" else "ʌ"
        "ER" -> if (stress == '0') "ɚ" else "ɜː"
        else -> VOWELS[base] ?: ""
    }

    // --- number normalisation (integers up to 999,999,999) ---
    private fun expandNumbers(text: String) =
        Regex("\\d+").replace(text) { " " + numberToWords(it.value.take(9).toLong()) + " " }

    private fun numberToWords(n: Long): String {
        if (n == 0L) return "zero"
        val parts = mutableListOf<String>()
        var rest = n
        for ((size, name) in listOf(1_000_000L to "million", 1_000L to "thousand")) {
            if (rest >= size) { parts += numberToWords(rest / size) + " " + name; rest %= size }
        }
        if (rest >= 100) { parts += ONES[(rest / 100).toInt()] + " hundred"; rest %= 100 }
        if (rest >= 20) {
            parts += TENS[(rest / 10).toInt()] + if (rest % 10 > 0) " " + ONES[(rest % 10).toInt()] else ""
        } else if (rest > 0) parts += ONES[rest.toInt()]
        return parts.joinToString(" ")
    }

    private companion object {
        val TOKEN = Regex("[A-Za-z']+|[.,!?;:—-]")
        val ONES = listOf("", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
        val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        val VOWELS = mapOf(
            "AA" to "ɑː", "AE" to "æ", "AO" to "ɔː", "AW" to "aʊ", "AY" to "aɪ", "EH" to "ɛ",
            "EY" to "eɪ", "IH" to "ɪ", "IY" to "iː", "OW" to "oʊ", "OY" to "ɔɪ", "UH" to "ʊ", "UW" to "uː",
        )
        val CONSONANTS = mapOf(
            "B" to "b", "CH" to "tʃ", "D" to "d", "DH" to "ð", "F" to "f", "G" to "ɡ", "HH" to "h",
            "JH" to "dʒ", "K" to "k", "L" to "l", "M" to "m", "N" to "n", "NG" to "ŋ", "P" to "p",
            "R" to "ɹ", "S" to "s", "SH" to "ʃ", "T" to "t", "TH" to "θ", "V" to "v", "W" to "w",
            "Y" to "j", "Z" to "z", "ZH" to "ʒ",
        )
    }
}
