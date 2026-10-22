package com.odify.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.content.Intent
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

data class OdifySong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val filePath: String,
    val artworkPath: String? = null,
    val durationMs: Long = 0L,
    val sourceUri: String? = null,
    val genre: String = "",
    val lyrics: String = ""
) {
    val isCloud: Boolean get() = id.startsWith("gdrive:") || sourceUri?.startsWith("https://") == true && !id.startsWith("localfolder:")
    val isPhoneFolder: Boolean get() = id.startsWith("localfolder:")
    fun mediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(if (!sourceUri.isNullOrBlank()) Uri.parse(sourceUri) else Uri.fromFile(File(filePath)))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .apply {
                    if (!artworkPath.isNullOrBlank()) {
                        setArtworkUri(Uri.fromFile(File(artworkPath)))
                    }
                }.build()
        ).build()
}

/** Owns temporary pilot imports. Never scans the user's whole Google Drive. */
class OdifyLibrary(private val context: Context) {
    private val prefs = context.getSharedPreferences("odify_native_library_v1", Context.MODE_PRIVATE)
    private val library = File(context.filesDir, "odify_audio").apply { mkdirs() }
    private val covers = File(context.filesDir, "odify_covers").apply { mkdirs() }

    fun songs(): List<OdifySong> {
        val raw = prefs.getString("songs", "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val value = array.optJSONObject(index) ?: return@mapNotNull null
                val source = value.optString("filePath")
                val cloudUri = value.optString("sourceUri").takeIf { it.isNotBlank() }
                if (cloudUri == null && (source.isEmpty() || !File(source).isFile)) {
                    return@mapNotNull null
                }
                OdifySong(
                    id = value.optString("id"),
                    title = value.optString("title"),
                    artist = value.optString("artist"),
                    album = value.optString("album"),
                    filePath = source,
                    artworkPath = value.optString("artworkPath").takeIf { it.isNotEmpty() },
                    durationMs = value.optLong("durationMs"),
                    sourceUri = cloudUri,
                    genre = value.optString("genre"),
                    lyrics = value.optString("lyrics")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun store(songs: List<OdifySong>) {
        val json = JSONArray()
        songs.forEach { song ->
            json.put(JSONObject().apply {
                put("id", song.id)
                put("title", song.title)
                put("artist", song.artist)
                put("album", song.album)
                put("filePath", song.filePath)
                put("artworkPath", song.artworkPath ?: "")
                put("durationMs", song.durationMs)
                put("sourceUri", song.sourceUri ?: "")
                put("genre", song.genre)
                put("lyrics", song.lyrics)
            })
        }
        prefs.edit().putString("songs", json.toString()).apply()
    }

    /**
     * Stream-from-provider option: retain Android DocumentsUI READ URI permission.
     * This does NOT create a full local audio copy. Google's DocumentsProvider may
     * still internally prefetch data, and HTTP range playback is not guaranteed.
     */
    suspend fun linkCloudDocuments(uris: List<Uri>): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(uris.size <= 100) { "한 번에 최대 100곡을 선택할 수 있습니다." }
                var existing = songs()
                var added = 0
                for (uri in uris) {
                    require(uri.scheme == "content") { "클라우드 파일 URI가 아닙니다." }
                    val name = displayName(uri)
                    val ext = name.substringAfterLast(".", "").lowercase(Locale.ROOT)
                    require(ext in setOf("mp3", "m4a", "aac", "flac", "ogg", "wav", "opus")) {
                        "지원하지 않는 음원: $name"
                    }
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    val canonical = uri.toString()
                    val id = "drive-" + MessageDigest.getInstance("SHA-256")
                        .digest(canonical.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    if (existing.any { it.id == id }) continue
                    val base = name.substringBeforeLast(".")
                    val parts = base.split(" - ", limit = 2)
                    val title = if (parts.size == 2) parts[1] else base
                    val artist = if (parts.size == 2) parts[0] else ""
                    // Read embedded title/artist/cover through Android's document
                    // descriptor. No Odify-owned full MP3 copy is created.
                    // Providers may fetch/cache parts of the remote file internally.
                    val tagged = extractCloudMetadata(uri, title, artist, id)
                    existing = existing + tagged
                    store(existing)
                    added++
                }
                added
            }
        }

    suspend fun importFiles(uris: List<Uri>): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            require(uris.size <= 20) { "한 번에 최대 20곡을 선택할 수 있습니다." }
            val existing = songs().toMutableList()
            val existingHashes = existing.mapTo(mutableSetOf()) { it.id }
            var added = 0
            for (uri in uris) {
                val name = displayName(uri)
                val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                require(extension in setOf("mp3", "m4a", "aac", "flac", "ogg", "wav", "opus")) {
                    "지원하지 않는 파일: $name"
                }
                val temp = File.createTempFile("odify_", ".tmp", library)
                var byteCount = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                try {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("이 파일을 열 수 없습니다: $name")
                    input.use { source ->
                        FileOutputStream(temp).use { destination ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val count = source.read(buffer)
                                if (count < 0) break
                                byteCount += count
                                if (byteCount > 100L * 1024L * 1024L) {
                                    error("100MB보다 큰 곡은 아직 지원하지 않습니다.")
                                }
                                digest.update(buffer, 0, count)
                                destination.write(buffer, 0, count)
                            }
                            destination.fd.sync()
                        }
                    }
                    require(byteCount > 0L) { "빈 파일은 가져올 수 없습니다." }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    val target = File(library, "$hash.$extension")
                    if (!target.exists() && !temp.renameTo(target)) {
                        error("음악 파일을 보관하지 못했습니다.")
                    }
                    if (existingHashes.add(hash)) {
                        val meta = extractMetadata(target, name, hash)
                        existing.add(meta)
                        store(existing) // persist after each successful import
                        added++
                    }
                } finally {
                    temp.delete()
                }
            }
            added
        }
    }

    /** User-owned metadata overrides survive restarts without mutating original Drive audio. */
    suspend fun updateMetadata(
        song: OdifySong, title: String, artist: String, album: String
    ) = withContext(Dispatchers.IO) {
        val safeTitle = title.trim()
        require(safeTitle.isNotEmpty()) { "곡명을 입력해 주세요." }
        val updated = songs().map {
            if (it.id == song.id) it.copy(
                title = safeTitle,
                artist = artist.trim(),
                album = album.trim()
            ) else it
        }
        store(updated)
    }

    suspend fun delete(song: OdifySong) = withContext(Dispatchers.IO) {
        val remaining = songs().filterNot { it.id == song.id }
        store(remaining)
        if (!song.isCloud) {
            File(song.filePath).delete()
        } else {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(song.sourceUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        song.artworkPath?.let { File(it).delete() }
    }

    private fun extractCloudMetadata(
        uri: Uri, fallbackTitle: String, fallbackArtist: String, id: String
    ): OdifySong {
        var title = fallbackTitle
        var artist = fallbackArtist
        var album = ""
        var duration = 0L
        var cover: String? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: fallbackTitle
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() } ?: fallbackArtist
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?: ""
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val picture = retriever.embeddedPicture
            if (picture != null && picture.size <= 10 * 1024 * 1024) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(picture, 0, picture.size, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    var scale = 1
                    while (bounds.outWidth / scale > 768 || bounds.outHeight / scale > 768) {
                        scale *= 2
                    }
                    val bitmap = BitmapFactory.decodeByteArray(
                        picture, 0, picture.size,
                        BitmapFactory.Options().apply {
                            inSampleSize = scale
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                    )
                    if (bitmap != null) {
                        val destination = File(covers, "$id.jpg")
                        FileOutputStream(destination).use { output ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
                        }
                        bitmap.recycle()
                        cover = destination.absolutePath
                    }
                }
            }
        } catch (_: Exception) {
            // Remote metadata extraction is best effort: a provider can reject
            // random reads, but media playback should remain accessible.
        } finally {
            runCatching { retriever.release() }
        }
        return OdifySong(
            id = id, title = title, artist = artist, album = album,
            filePath = "", artworkPath = cover,
            durationMs = duration, sourceUri = uri.toString()
        )
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val value = cursor.getString(0)
                    if (!value.isNullOrBlank()) return value
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "unknown.mp3"
    }

    private fun extractMetadata(file: File, filename: String, hash: String): OdifySong {
        val base = filename.substringBeforeLast('.')
        val pieces = base.split(" - ", limit = 2)
        val fallbackArtist = if (pieces.size == 2) pieces[0] else ""
        val fallbackTitle = if (pieces.size == 2) pieces[1] else base
        var title = fallbackTitle
        var artist = fallbackArtist
        var album = ""
        var duration = 0L
        var cover: String? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: fallbackTitle
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() } ?: fallbackArtist
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val bytes = retriever.embeddedPicture
            if (bytes != null && bytes.size <= 10 * 1024 * 1024) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    var scale = 1
                    while (bounds.outWidth / scale > 768 || bounds.outHeight / scale > 768) {
                        scale *= 2
                    }
                    val bitmap = BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.size,
                        BitmapFactory.Options().apply {
                            inSampleSize = scale
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                    )
                    if (bitmap != null) {
                        val target = File(covers, "$hash.jpg")
                        FileOutputStream(target).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                        bitmap.recycle()
                        cover = target.absolutePath
                    }
                }
            }
        } catch (_: Exception) {
            // Unsupported metadata should never block MP3/FLAC import.
        } finally {
            runCatching { retriever.release() }
        }
        return OdifySong(hash, title, artist, album, file.absolutePath, cover, duration)
    }
}
