package com.odify.nativeapp

/** Presentation only, after the catalog has established the artist identity.
 * Never deletes source entries, changes saved playlists, or joins by substring. */
internal object OdifyArtistTracks {
    private val brackets=Regex("[\\[（(【]([^\\]）)】]*)[\\]）)】]")
    private val badge=Regex("(?i)^(?:(?:official|music|video|audio|lyrics?|visuali[sz]er|m/?v|4k|hd|가사|歌詞|\\s|[-_/]))+$")
    private val remaster=Regex("(?i)^(?:[12][0-9]{3}\\s*)?re-?master(?:ed)?(?:\\s*(?:version|[12][0-9]{3}))*$")
    private val separator=Regex("\\s+[-–—|:]\\s+")
    private val differentVersion=Regex("(?i)\\b(live|cover|remix|concert|karaoke|instrumental|acoustic|sped|slowed)\\b|라이브|커버|콘서트|歌ってみた")
    private fun decoration(text:String)=text.isNotBlank() && (badge.matches(text.trim()) || remaster.matches(text.trim()))
    internal fun titleKey(title:String,artist:String,aliases:List<String> = emptyList()):String {
        var value=title.trim()
        val names=(aliases+artist).map(OdifyMusicMatching::norm).filter(String::isNotBlank).toSet()
        separator.find(value)?.let {first ->
            if(OdifyMusicMatching.norm(value.substring(0,first.range.first)) in names)
                value=value.substring(first.range.last+1).trim()
        }
        value=brackets.replace(value) {match ->if(decoration(match.groupValues[1]))" " else match.value}
        while(true) {
            val last=separator.findAll(value).lastOrNull() ?: break
            if(!decoration(value.substring(last.range.last+1)))break
            value=value.substring(0,last.range.first).trim()
        }
        return OdifyMusicMatching.norm(value)
    }
    fun owned(songs:List<OdifySong>,artist:String,aliases:List<String> = emptyList()):List<OdifySong> {
        val result=LinkedHashMap<String,OdifySong>()
        fun rank(song:OdifySong)=(if(!song.isYoutubeStream)100 else 0)+(if(!song.isCloud)10 else 0)+
            (if(!song.artworkPath.isNullOrBlank())4 else 0)
        for(song in songs) {
            val key=titleKey(song.title,artist,aliases+song.artist).ifBlank {"id:${song.id}"}
            val old=result[key]
            if(old==null || rank(song)>rank(old))result[key]=song
        }
        return result.values.toList()
    }
    fun additional(tracks:List<ExploreTrack>,owned:List<OdifySong>,artist:String):List<ExploreTrack> {
        val aliases=tracks.mapNotNull {it.music?.artist}.distinct()
        val known=owned.map {titleKey(it.title,artist,aliases+it.artist)}.filter(String::isNotBlank).toSet()
        val result=LinkedHashMap<String,ExploreTrack>()
        fun rank(track:ExploreTrack)=(if(track.music!=null)100 else 0)+(if(track.artwork.isNotBlank())4 else 0)+
            (if(remaster.containsMatchIn(track.title) || track.title.contains("remaster",true))0 else 1)
        for(track in tracks) {
            val title=titleKey(track.title,artist,aliases)
            if(title.isNotBlank() && title in known)continue
            // Separate unverified live/cover uploads can be different takes or
            // performers even when their upload titles happen to be identical.
            val key=if(title.isBlank() || track.music==null && differentVersion.containsMatchIn(track.title))"id:${track.id}" else title
            val old=result[key]
            if(old==null || rank(track)>rank(old))result[key]=track
        }
        return result.values.toList()
    }
}
