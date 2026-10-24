package com.odify.nativeapp

import java.text.Normalizer
import java.util.Locale

/** Link YouTube playlist metadata to audio that the user already owns in Odify.
 * Never treat similarly named covers, remixes or live performances as exact.
 */
object OdifyYouTubeMatcher {
    private val extras = Regex("""(?i)\s*(\((?:official\s*(?:music\s*)?(?:video|audio)|mv|audio|lyrics?|audio\s*only|visualizer)\)|\[(?:official\s*(?:music\s*)?(?:video|audio)|mv|audio|lyrics?|visualizer)])""")
    fun norm(input: String): String = Normalizer.normalize(input,Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"),"")
    fun videoId(url: String): String? {
        val pattern=Regex("^[A-Za-z0-9_-]{11}$")
        val uri=runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
        val h=uri.host?.lowercase(Locale.ROOT) ?: return null
        val id=when(h) {
            "youtube.com","www.youtube.com","m.youtube.com","music.youtube.com" ->
                uri.getQueryParameter("v")
            "youtu.be" -> uri.pathSegments.firstOrNull()
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
        val target=master.firstOrNull {
            it.verified && videoId(it.youtubeUrl)==video.videoId
        }
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
        // A YouTube title like 'Artist - Song (Official Audio)' is only a
        // candidate, not enough to conclude the correct audio version.
        val display=video.title.replace(extras,"").trim()
        val fragments=display.split(" - ",limit=2)
        if(fragments.size!=2) return null
        val artist=norm(fragments[0])
        val title=norm(fragments[1])
        val possible=tracks.filter {
            norm(it.title)==title && norm(it.artist)==artist
        }
        return possible.singleOrNull()
    }
}
