package com.odify.nativeapp

/**
 * Prefer explicit native-script title and artist names.
 * This is not automatic machine translation: when original spelling is absent
 * we keep the source tag, except for a verified exact-name equivalence.
 */
object OdifyTitlePolicy {
    private val japanese = Regex("[\\u3040-\\u30ff\\u3400-\\u9fff]")
    private val hangul = Regex("[\\uac00-\\ud7a3]")
    private val parentheses=Regex("[(（]([^()（）]+)[)）]")

    fun title(raw: String, artist: String): String {
        if (artist.equals("SOYEON", true) || artist == "소연 (SOYEON)" ||
            artist == "소연") {
            if (raw.equals("I'm gonna TOESA (Narr. KIAN84)",true) ||
                raw.equals("I’m gonna TOESA (Narr. KIAN84)",true))
                return "퇴사할게여 (Narr. 기안84)"
        }
        // Example: "또 만나자가 있다면(またねがあれば)" → "またねがあれば".
        val nativeTitle=if(artist.equals("YOASOBI",true) && OdifyMusicMatching.norm(raw)=="anoyumewonazotte")
            "あの夢をなぞって" else raw
        val head=nativeTitle.substringBefore('(').substringBefore('（')
        if(japanese.containsMatchIn(head))return nativeTitle
        val nested=parentheses.findAll(nativeTitle).map {it.groupValues[1].trim()}.toList()
        return nested.firstOrNull {japanese.containsMatchIn(it)}
            ?: nested.firstOrNull {!isNativeWriting(head) && hangul.containsMatchIn(it)}
            ?: nativeTitle
    }

    internal fun storefront(recording:OdifyMusicIdentity):String? = when {
        OdifyMusicMatching.norm(recording.genre) in setOf("jpop","kayokyoku","歌謡曲","제이팝") -> "jp"
        OdifyMusicMatching.norm(recording.genre) in setOf("kpop","케이팝","한국가요") -> "kr"
        hangul.containsMatchIn(recording.artist) -> "kr"
        japanese.containsMatchIn(recording.artist) -> "jp"
        else -> Regex("^https://music\\.apple\\.com/(jp|kr)/").find(recording.trackUrl)?.groupValues?.get(1)
    }

    internal fun storefrontTitle(recording:OdifyMusicIdentity,local:OdifyMusicIdentity,storefront:String):String? {
        if(recording.id!=local.id || recording.artistId.isBlank() || recording.artistId!=local.artistId)return null
        val title=title(local.title,local.artist)
        return title.takeIf {when(storefront) {
            "jp" -> japanese.containsMatchIn(it) && !hangul.containsMatchIn(it)
            "kr" -> hangul.containsMatchIn(it)
            else -> false
        }}
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
