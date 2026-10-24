package com.odify.nativeapp

/** A chart identity or exact full artist/title; never a title/lyric substring. */
object OdifyChartMatching {
    class Index internal constructor(private val chartIds:Map<String,OdifySong>,
        private val metadata:Map<Pair<String,String>,OdifySong>,
        private val untagged:Map<String,OdifySong>) {
        fun resolve(track:ExploreTrack):OdifySong?=chartIds[track.id] ?:
            metadata[OdifyYouTubeMatcher.norm(track.artist) to OdifyYouTubeMatcher.norm(track.title)] ?:
            untagged[OdifyYouTubeMatcher.norm(track.title)]
    }
    fun buildIndex(songs:List<OdifySong>):Index {
        val visible=OdifySongPresentation.visible(songs)
        val byHash=visible.filter {it.contentMd5.isNotBlank()}.associateBy {it.contentMd5}
        val chartIds=LinkedHashMap<String,OdifySong>()
        for(song in songs) {
            val preferred=byHash[song.contentMd5] ?: song
            for(id in listOf(song.chartId)+song.chartAliases) if(id.isNotBlank()) chartIds.putIfAbsent(id,preferred)
        }
        val metadata=LinkedHashMap<Pair<String,String>,OdifySong>()
        // A byte-identical offline preference must not discard the cloud copy's tags.
        for(song in songs) {
            val artist=OdifyYouTubeMatcher.norm(song.artist);val title=OdifyYouTubeMatcher.norm(song.title)
            if(artist.isNotBlank() && title.isNotBlank()) metadata.putIfAbsent(artist to title,byHash[song.contentMd5] ?: song)
        }
        // Personal files often have no artist tag. Only one exact-title recording
        // may supply that fallback; known conflicting artists/versions never do.
        val untagged=visible.groupBy {OdifyYouTubeMatcher.norm(it.title)}.mapNotNull {(title,group)->
            group.singleOrNull()?.takeIf {it.artist.isBlank() && title.isNotBlank() &&
                (it.durationMs==0L || it.durationMs in 60_000L..900_000L)}?.let {title to it}
        }.toMap()
        return Index(chartIds,metadata,untagged)
    }
    fun resolve(track: ExploreTrack, songs: List<OdifySong>): OdifySong?=buildIndex(songs).resolve(track)
    fun storedReference(id:String,songs:List<OdifySong>):OdifySong? =
        songs.firstOrNull {it.id==id} ?: id.takeIf {it.startsWith("chart:")}
            ?.removePrefix("chart:")?.let {chartId -> songs.firstOrNull {it.chartId==chartId || chartId in it.chartAliases}}
}
