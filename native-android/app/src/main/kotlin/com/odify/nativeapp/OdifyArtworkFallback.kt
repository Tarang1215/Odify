package com.odify.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fallback to the verified MASTER YouTube video thumbnail only when a sound
 * file has no embedded artwork. Does NOT download audio/video from YouTube.
 * Art-track thumbnails may differ from the official album cover.
 */
class OdifyArtworkFallback(private val context: Context) {
    private val prefs = context.getSharedPreferences("odify_thumbnail_retry", 0)
    private val folder = File(context.filesDir, "yt_art").apply { mkdirs() }
    private val videoId = Regex("^[A-Za-z0-9_-]{11}$")

    private fun extractVideoId(url: String): String? {
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

    suspend fun warm(
        catalog: DriveCatalog, songs: List<OdifySong>, master: List<MasterTrack>,
        limit: Int = 12
    ): Int = withContext(Dispatchers.IO) {
        val candidates = songs.filter { it.id.startsWith("gdrive:") && it.artworkPath.isNullOrBlank() }
        var count = 0
        for (song in candidates) {
            if (count >= limit) break
            val id = song.id.removePrefix("gdrive:")
            val lastFailed = prefs.getLong("fail:$id", 0L)
            if (System.currentTimeMillis()-lastFailed < 24L*60L*60L*1000L) continue
            val match = master.firstOrNull {
                it.verified && it.driveId == id
            } ?: master.firstOrNull {
                it.verified && it.driveId.isBlank() &&
                    MasterDuplicateDetector.norm(it.artist) ==
                        MasterDuplicateDetector.norm(song.artist) &&
                    MasterDuplicateDetector.norm(it.title) ==
                        MasterDuplicateDetector.norm(song.title)
            } ?: continue
            val video = extractVideoId(match.youtubeUrl) ?: continue
            // Only use an exact video ID already verified by the user's
            // master. Never fuzzy-match some unrelated YouTube result.
            val pic = runCatching { download(video) }.getOrNull()
            if (pic == null) {
                prefs.edit().putLong("fail:$id", System.currentTimeMillis()).apply()
                continue
            }
            val saved = File(folder, "$id.jpg")
            if (runCatching { saved.outputStream().use {
                    require(pic.compress(Bitmap.CompressFormat.JPEG, 85, it))
                }; pic.recycle() }.isFailure) {
                pic.recycle()
                continue
            }
            catalog.setFallbackArtwork(id, saved.absolutePath)
            count++
        }
        count
    }

    private fun download(video: String): Bitmap? {
        val url = URL("https://i.ytimg.com/vi/$video/hqdefault.jpg")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000; conn.readTimeout = 8_000
            conn.instanceFollowRedirects = false
            if (conn.responseCode != 200 ||
                !conn.contentType.orEmpty().startsWith("image/")) return null
            val bytes = conn.inputStream.use { it.readNBytes(650_001) }
            if (bytes.size > 650_000) return null
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            return if (bmp.width <= 768 && bmp.height <= 768) bmp
                else Bitmap.createScaledBitmap(bmp, 768, 768 * bmp.height / bmp.width, true)
                    .also { bmp.recycle() }
        } finally { conn.disconnect() }
    }
}
