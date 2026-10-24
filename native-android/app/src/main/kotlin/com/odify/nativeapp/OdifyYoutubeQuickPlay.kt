package com.odify.nativeapp

/** User-selected fallback heuristic, never recorded as a verified source link. */
internal object OdifyYoutubeQuickPlay {
    private val excluded = Regex("(?i)(\\b(live|concert|cover|karaoke|remix|sped|slowed|tutorial|lesson|instrumental)\\b|콘서트|라이브|커버|강좌|연주법|歌ってみた|弾いてみた)")
    fun query(artist: String, title: String): String =
        (listOf(artist.trim(), title.trim()).filter { it.isNotBlank() }.joinToString(" ").take(150) + " lyrics").trim()
    fun first(videos: List<YoutubeVideo>): YoutubeVideo? = videos.firstOrNull {
        Regex("[A-Za-z0-9_-]{11}").matches(it.videoId) && !excluded.containsMatchIn(it.title)
    }
}
