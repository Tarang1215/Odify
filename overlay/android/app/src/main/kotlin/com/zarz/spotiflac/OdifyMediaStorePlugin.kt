package com.zarz.spotiflac

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

/** Read shared audio indexed by Android; never access Samsung Music private data. */
class OdifyMediaStorePlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private var channel: MethodChannel? = null
    private var applicationContext: Context? = null
    private val worker = Executors.newSingleThreadExecutor()
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
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method != "listMusic") {
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
                    ))
                } while (tracks.size < limit && cursor.moveToNext())
            }
        }
        return tracks
    }
}
