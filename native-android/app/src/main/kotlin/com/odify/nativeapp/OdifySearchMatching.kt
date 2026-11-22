package com.odify.nativeapp

enum class OdifySearchScope { Music, Lyrics }

/** Lyrics are an explicit search destination, never a silent chart-title match. */
object OdifySearchMatching {
    fun matches(song: OdifySong, query: String, scope: OdifySearchScope): Boolean {
        val term = query.trim()
        if (term.isEmpty()) return true
        return when (scope) {
            OdifySearchScope.Music -> listOf(song.title, song.catalogTitle, song.artist, song.album, song.genre)
                .any { it.contains(term, ignoreCase = true) } || OdifyDiscovery.sameArtist(song.artist,term) ||
                OdifyMusicMatching.titleKey(term,song.artist).let {it.isNotBlank() && it==OdifyMusicMatching.titleKey(song.title,song.artist)}
            OdifySearchScope.Lyrics -> song.lyrics.contains(term, ignoreCase = true)
        }
    }
}
