package com.odify.nativeapp

/** A chart identity or exact full artist/title; never a title/lyric substring. */
object OdifyChartMatching {
    fun resolve(track: ExploreTrack, songs: List<OdifySong>): OdifySong? {
        val visible=OdifySongPresentation.visible(songs)
        songs.firstOrNull {it.chartId==track.id || track.id in it.chartAliases}?.let {identified ->
            return visible.firstOrNull {identified.contentMd5.isNotBlank() && it.contentMd5==identified.contentMd5} ?: identified
        }
        val title=OdifyYouTubeMatcher.norm(track.title)
        val artist=OdifyYouTubeMatcher.norm(track.artist)
        if(title.isBlank() || artist.isBlank()) return null
        return visible.firstOrNull {OdifyYouTubeMatcher.norm(it.title)==title &&
            OdifyYouTubeMatcher.norm(it.artist)==artist}
    }
    fun storedReference(id:String,songs:List<OdifySong>):OdifySong? =
        songs.firstOrNull {it.id==id} ?: id.takeIf {it.startsWith("chart:")}
            ?.removePrefix("chart:")?.let {chartId -> songs.firstOrNull {it.chartId==chartId || chartId in it.chartAliases}}
}
