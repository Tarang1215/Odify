package com.zarz.spotiflac

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import java.io.File
import java.io.FileOutputStream

/** Read shared audio indexed by Android; never access Samsung Music private data. */
class OdifyMediaStorePlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private var channel: MethodChannel? = null
    private var applicationContext: Context? = null
    private val worker = Executors.newSingleThreadExecutor()
    // Keep artwork decoding from blocking paged song-list requests.
    private val artworkWorker = Executors.newSingleThreadExecutor()
    private val mainThread = Handler(Looper.getMainLooper())

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "com.odify.music/media_store")
        channel?.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
        applicationContext = null
        worker.shutdownNow()
        artworkWorker.shutdownNow()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method != "listMusic" && call.method != "getArtwork") {
            result.notImplemented()
            return
        }
        val context = applicationContext ?: run {
            result.error("unavailable", "Android media access is unavailable", null)
            return
        }
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            result.error("permission_denied", "Grant music access to browse device audio", null)
            return
        }
        if (call.method == "getArtwork") {
            // Only numeric MediaStore IDs are accepted. Never read caller paths,
            // issue network requests, or expose full embedded image bytes to Dart.
            val mediaId = call.argument<String>("id")?.toLongOrNull()
            if (mediaId == null || mediaId <= 0L) {
                result.error("bad_media_id", "Invalid MediaStore audio ID", null)
                return
            }
            artworkWorker.execute {
                try {
                    val path = extractCachedCover(context, mediaId)
                    mainThread.post { result.success(path) }
                } catch (_: Exception) {
                    // Missing/corrupt embedded pictures are an expected fallback.
                    mainThread.post { result.success(null) }
                }
            }
            return
        }
        val offset = (call.argument<Int>("offset") ?: 0).coerceIn(0, 20000)
        val limit = (call.argument<Int>("limit") ?: 150).coerceIn(1, 300)
        worker.execute {
            try {
                val tracks = queryMusic(context, offset, limit)
                mainThread.post { result.success(tracks) }
            } catch (error: Exception) {
                mainThread.post { result.error("media_query_failed", error.message, null) }
            }
        }
    }

    // Source-versioned cover cache, stored only in Odify's private directory.
    // Exports scaled JPEG thumbnails rather than copying users' MP3 files.
    private fun coverDirectory(context: Context) =
        File(context.filesDir, "odify_media_artwork")

    private fun coverFile(context: Context, id: Long, modifiedSeconds: Long) =
        File(coverDirectory(context), "${id}_${modifiedSeconds}.jpg")

    private fun cachedCoverPath(context: Context, id: Long, modifiedSeconds: Long): String? {
        val file = coverFile(context, id, modifiedSeconds)
        return file.absolutePath.takeIf { file.isFile && file.length() > 0L }
    }

    private fun lookupModifiedSeconds(context: Context, id: Long): Long? {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        context.contentResolver.query(
            uri, arrayOf(MediaStore.Audio.Media.DATE_MODIFIED), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    private fun extractCachedCover(context: Context, id: Long): String? {
        val modified = lookupModifiedSeconds(context, id) ?: return null
        val file = coverFile(context, id, modified)
        if (file.isFile && file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())
            return file.absolutePath
        }
        val mediaUri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
        )
        val retriever = MediaMetadataRetriever()
        val embedded: ByteArray
        try {
            retriever.setDataSource(context, mediaUri)
            embedded = retriever.embeddedPicture ?: return null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        // Bound pathological embedded art before bitmap decoding.
        if (embedded.size > 12 * 1024 * 1024) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(embedded, 0, embedded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 768 || bounds.outHeight / sample > 768) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(
            embedded, 0, embedded.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        ) ?: return null
        val thumb = try {
            val ratio = minOf(1.0, 512.0 / maxOf(bitmap.width, bitmap.height))
            val width = maxOf(1, (bitmap.width * ratio).toInt())
            val height = maxOf(1, (bitmap.height * ratio).toInt())
            if (width == bitmap.width && height == bitmap.height) bitmap
            else Bitmap.createScaledBitmap(bitmap, width, height, true)
        } catch (_: Exception) {
            bitmap.recycle()
            return null
        }
        val directory = file.parentFile ?: return null
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val temp = File(directory, "${file.name}.tmp")
        try {
            val success = FileOutputStream(temp).use { output ->
                thumb.compress(Bitmap.CompressFormat.JPEG, 82, output)
            }
            if (!success) return null
            if (!temp.renameTo(file)) return null
            // Periodic cap prevents thousands of stale covers growing forever.
            // A scrolled-off entry simply regenerates if LRU eviction occurs.
            cacheWrites++
            if (cacheWrites % 32 == 0) pruneArtworkCache(context)
            return file.absolutePath
        } finally {
            if (thumb !== bitmap) thumb.recycle()
            bitmap.recycle()
            if (temp.exists()) temp.delete()
        }
    }

    private var cacheWrites = 0

    private fun pruneArtworkCache(context: Context) {
        val files = coverDirectory(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jpg") }
            ?.sortedBy { it.lastModified() } ?: return
        if (files.size <= 600) return
        for (stale in files.take(files.size - 480)) stale.delete()
    }

    private fun queryMusic(context: Context, offset: Int, limit: Int): List<Map<String, Any?>> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.ALBUM_ID,
        )
        val tracks = ArrayList<Map<String, Any?>>(limit)
        val cursor = context.contentResolver.query(
            collection, columns,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.DATE_MODIFIED} DESC, ${MediaStore.Audio.Media._ID} DESC",
        ) ?: error("MediaStore query returned null")
        cursor.use {
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val albumIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            if (cursor.moveToPosition(offset)) {
                do {
                    val id = cursor.getLong(idIndex)
                    tracks.add(mapOf(
                        "id" to id.toString(),
                        "uri" to ContentUris.withAppendedId(collection, id).toString(),
                        "title" to cursor.getString(titleIndex).orEmpty(),
                        "artist" to cursor.getString(artistIndex).orEmpty(),
                        "album" to cursor.getString(albumIndex).orEmpty(),
                        "durationMs" to cursor.getLong(durationIndex),
                        "modifiedSeconds" to cursor.getLong(modifiedIndex),
                        "albumId" to cursor.getLong(albumIdIndex),
                        "cachedCoverPath" to cachedCoverPath(
                            context, id, cursor.getLong(modifiedIndex)
                        ),
                    ))
                } while (tracks.size < limit && cursor.moveToNext())
            }
        }
        return tracks
    }
}
