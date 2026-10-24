package com.odify.nativeapp

import java.text.Normalizer
import java.util.Locale
import java.net.URI
import java.net.URLDecoder

/** Link YouTube playlist metadata to audio that the user already owns in Odify.
 * Never treat similarly named covers, remixes or live performances as exact.
 */
object OdifyYouTubeMatcher {
    fun norm(input: String): String = Normalizer.normalize(input,Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"),"")
    fun videoId(url: String): String? {
        val pattern=Regex("^[A-Za-z0-9_-]{11}$")
        val uri=runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1) return null
        val h=uri.host?.lowercase(Locale.ROOT) ?: return null
        val id=when(h) {
            "youtube.com","www.youtube.com","m.youtube.com","music.youtube.com" -> {
                if (uri.path != "/watch") return null
                val values = runCatching { uri.rawQuery.orEmpty().split('&').mapNotNull {
                    val parts = it.split('=', limit=2)
                    if (URLDecoder.decode(parts[0], "UTF-8") == "v")
                        URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8") else null
                } }.getOrNull() ?: return null
                values.singleOrNull()
            }
            "youtu.be" -> uri.path.removePrefix("/")
            else -> null
        }
        return id?.takeIf { pattern.matches(it) }
    }

    fun find(
        video: YoutubeVideo,
        tracks: List<OdifySong>,
        master: List<MasterTrack>
    ): OdifySong? {
        // Best match: known official video ID from verified user's Google Sheets MASTER.
        val targets=master.filter {
            it.verified && videoId(it.youtubeUrl)==video.videoId
        }
        val target=targets.distinctBy { Triple(it.trackId, it.artist, it.driveId) }.singleOrNull()
        if(target!=null) {
            val exact=tracks.firstOrNull {
                it.id=="gdrive:${target.driveId}" && target.driveId.isNotBlank()
            }
            if(exact!=null) return exact
            val names=tracks.filter {
                norm(it.title)==norm(target.title) &&
                    norm(it.artist)==norm(target.artist)
            }
            if(names.size==1) return names.first()
            return null
        }
        // A matching display title cannot establish recording identity.
        // Keep unverified/ambiguous candidates unlinked until MASTER review.
        return null
    }
}
