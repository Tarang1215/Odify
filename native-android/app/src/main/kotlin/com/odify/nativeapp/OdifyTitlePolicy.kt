package com.odify.nativeapp

import java.util.Locale

/**
 * Prefer explicit native-script title and artist names.
 * This is not automatic machine translation: when original spelling is absent
 * we keep the source tag, except for a verified exact-name equivalence.
 */
object OdifyTitlePolicy {
    private val japanese = Regex("[\\u3040-\\u30ff\\u3400-\\u9fff]")
    private val hangul = Regex("[\\uac00-\\ud7a3]")

    fun title(raw: String, artist: String): String {
        if (artist.equals("SOYEON", true) || artist == "소연 (SOYEON)" ||
            artist == "소연") {
            if (raw.equals("I'm gonna TOESA (Narr. KIAN84)",true) ||
                raw.equals("I’m gonna TOESA (Narr. KIAN84)",true))
                return "퇴사할게여 (Narr. 기안84)"
        }
        // Example: "또 만나자가 있다면(またねがあれば)" → "またねがあれば".
        val nested = Regex("[(（]([^()（）]+)[)）]").findAll(raw)
            .map { it.groupValues[1].trim() }
            .firstOrNull { japanese.containsMatchIn(it) }
        return nested ?: raw
    }

    fun artist(raw: String): String {
        // "가시와기 린코(柏木凛子)" → "柏木凛子".
        val nested = Regex("[(（]([^()（）]+)[)）]").findAll(raw)
            .map { it.groupValues[1].trim() }
            .firstOrNull { japanese.containsMatchIn(it) }
        return nested ?: raw
    }

    fun isNativeWriting(text: String): Boolean =
        japanese.containsMatchIn(text) || hangul.containsMatchIn(text)
}
