package com.odify.nativeapp

import java.util.Locale

/**
 * Search/Home default to one visible track per same artist + same song title.
 * Nothing is removed from Drive/SQLite: the Search "중복 포함" option exposes
 * alternate MP3/M4A or duplicated folder copies at any time.
 */
object OdifySongPresentation {
    private val md5=Regex("[a-f0-9]{32}")
    private val whitespace=Regex("\\s+")
    private val fileCode=Regex("odf[0-9]{6}",RegexOption.IGNORE_CASE)
    fun visible(items: List<OdifySong>, showDuplicates: Boolean = false): List<OdifySong> {
        if (showDuplicates) return items
        fun rank(song:OdifySong)=(if(!song.isCloud) 10 else 0)+(if(song.artworkPath!=null) 4 else 0)
        val bytes=LinkedHashMap<String,OdifySong>()
        for(song in items) {
            val hash=song.contentMd5.lowercase(Locale.ROOT)
            val id=if(md5.matches(hash)) "md5:$hash" else "id:${song.id}"
            val old=bytes[id]
            if(old==null || rank(song)>rank(old)) bytes[id]=song
        }
        val buckets = LinkedHashMap<String, OdifySong>()
        for (song in bytes.values) {
            val title = song.title.trim().lowercase(Locale.ROOT)
                .replace(whitespace, " ")
            val artist = song.artist.trim().lowercase(Locale.ROOT)
                .replace(whitespace, " ")
            val normalized = if (artist.isNotBlank()) "$artist|$title"
                else if (fileCode.matches(title))
                    "filecode:$title"
                else "id:${song.id}"
            val current = buckets[normalized]
            if (current == null) {
                buckets[normalized] = song
            } else {
                // Prefer an offline file and then available album art, but do
                // not discard the other copy: it's accessible via "중복 포함".
                val newRank = (if (!song.isCloud) 10 else 0) +
                    (if (song.artworkPath != null) 4 else 0)
                val oldRank = (if (!current.isCloud) 10 else 0) +
                    (if (current.artworkPath != null) 4 else 0)
                if (newRank > oldRank) buckets[normalized] = song
            }
        }
        return buckets.values.toList()
    }
}
