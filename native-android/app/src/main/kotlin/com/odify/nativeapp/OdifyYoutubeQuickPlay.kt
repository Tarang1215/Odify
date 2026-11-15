package com.odify.nativeapp

/** User-selected fallback heuristic, never recorded as a verified source link. */
internal object OdifyYoutubeQuickPlay {
    private val excluded = Regex("(?i)(\\b(live|concert|cover|karaoke|remix|sped|slowed|tutorial|lesson|instrumental)\\b|콘서트|라이브|커버|강좌|연주법|歌ってみた|弾いてみた)")
    fun query(artist: String, title: String): String =
        (listOf(artist.trim(), title.trim()).filter { it.isNotBlank() }.joinToString(" ").take(150) + " lyrics").trim()
    private val videoId=Regex("[A-Za-z0-9_-]{11}")
    private fun allowed(video:YoutubeVideo)=videoId.matches(video.videoId) && !excluded.containsMatchIn(video.title)
    fun first(videos: List<YoutubeVideo>): YoutubeVideo? = videos.firstOrNull(::allowed)
    fun matching(videos:List<YoutubeVideo>,artist:String,title:String):YoutubeVideo? {
        val expected=OdifyMusicIdentity("",title,artist,"","","","","")
        return videos.firstOrNull {video->
            allowed(video) && OdifyMusicMatching.choose(video,listOf(expected))!=null
        }
    }
}
