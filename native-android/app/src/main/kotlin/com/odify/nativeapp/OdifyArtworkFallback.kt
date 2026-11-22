package com.odify.nativeapp

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class OdifyArtworkFallback(private val context: Context) {
    private val prefs = context.getSharedPreferences("odify_thumbnail_retry", 0)
    private val music=OdifyMusicCatalog(context)
    companion object {
    private val videoId = Regex("^[A-Za-z0-9_-]{11}$")
    internal fun isVideoThumbnail(path:String):Boolean = File(path).let {
        it.parentFile?.name=="yt_art" || it.parentFile?.name=="cache" && it.name.startsWith("youtube_search_")
    }

    internal fun extractVideoId(url: String): String? {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val candidate = when (host) {
            "www.youtube.com", "youtube.com", "m.youtube.com",
            "music.youtube.com" -> uri.getQueryParameter("v")
                ?: uri.pathSegments.takeIf { it.size >= 2 && it[0] in listOf("shorts","embed") }
                    ?.getOrNull(1)
            "youtu.be" -> uri.pathSegments.firstOrNull()
            else -> null
        }
        return candidate?.takeIf { videoId.matches(it) }
    }
    }

    suspend fun warm(
        catalog: DriveCatalog, songs: List<OdifySong>, master: List<MasterTrack>,
        limit: Int = 12
    ): Int = withContext(Dispatchers.IO) {
        val candidates=songs.asSequence().filter {song->
            song.id.startsWith("gdrive:") &&
                (song.artworkPath.isNullOrBlank() || isVideoThumbnail(song.artworkPath))
        }
        val verified=master.filter {it.verified && it.driveId.isNotBlank()}.groupBy {it.driveId}
        var count = 0
        var attempts=0
        for (song in candidates) {
            if(attempts>=limit)break
            val id = song.id.removePrefix("gdrive:")
            val lastFailed = prefs.getLong("album-fail:$id", 0L)
            if (System.currentTimeMillis()-lastFailed < 24L*60L*60L*1000L) continue
            val match=verified[id]?.singleOrNull()
            val artist=song.artist.ifBlank {match?.artist.orEmpty()}
            val title=song.title.ifBlank {match?.title.orEmpty()}
            if(artist.isBlank() || title.isBlank() || OdifyMusicMatching.isVideoVersion(title))continue
            attempts++
            val cover=try {music.recording(artist,title)?.let {music.cover(it)}}
            catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){null}
            if(cover==null) {
                prefs.edit().putLong("album-fail:$id",System.currentTimeMillis()).apply()
                continue
            }
            catalog.setFallbackArtwork(id,cover)
            count++
        }
        count
    }

}
