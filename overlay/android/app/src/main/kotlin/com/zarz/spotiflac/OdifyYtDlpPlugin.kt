package com.zarz.spotiflac

import android.content.Context
import android.os.Environment
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yausername.youtubedl_android.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File

/**
 * yt-dlp adapter for Odify. The UI never accesses expiring extracted URLs
 * directly until a user selects a song. All blocking work stays off the UI.
 * Only download content for which the user has appropriate rights.
 */
class OdifyYtDlpPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var context: Context
    private lateinit var channel: MethodChannel
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var initialized = false
    private val initLock = Any()

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "com.odify.music/yt_dlp")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        scope.cancel()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val arguments = call.arguments as? Map<*, *> ?: emptyMap<String, Any>()
        scope.launch {
            try {
                val value: Any = when (call.method) {
                    "search" -> search(
                        arguments["query"] as? String ?: "",
                        (arguments["limit"] as? Number)?.toInt()?.coerceIn(1, 30) ?: 30
                    )
                    "playlist" -> playlist(arguments["url"] as? String ?: "")
                    "resolve" -> resolve(arguments["videoId"] as? String ?: "")
                    "downloadMp3" -> downloadMp3(arguments["videoId"] as? String ?: "")
                    else -> throw IllegalArgumentException("Unknown command: ${call.method}")
                }
                withContext(Dispatchers.Main) { result.success(value) }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    result.error("odify_yt_dlp_error", error.message ?: "Audio operation failed", null)
                }
            }
        }
    }

    private fun setup(ffmpeg: Boolean = false) {
        if (!initialized) synchronized(initLock) {
            if (!initialized) {
                YoutubeDL.getInstance().init(context)
                initialized = true
            }
        }
        if (ffmpeg) FFmpeg.getInstance().init(context)
    }

    private fun videoUrl(id: String): String {
        require(Regex("[A-Za-z0-9_-]{11}").matches(id)) { "Invalid YouTube video id" }
        return "https://www.youtube.com/watch?v=$id"
    }

    private fun search(query: String, limit: Int): List<Map<String, Any>> {
        val trimmed = query.trim()
        require(trimmed.length in 1..160 && !trimmed.contains('\n')) { "Enter a valid search query" }
        setup()
        return flatItems("ytsearch$limit:$trimmed")
    }

    private fun playlist(url: String): List<Map<String, Any>> {
        val parsed = android.net.Uri.parse(url.trim())
        require(parsed.scheme == "https" &&
            parsed.host?.lowercase() in listOf("youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com") &&
            parsed.getQueryParameter("list")?.matches(Regex("[A-Za-z0-9_-]{10,}")) == true) {
            "Expected a public YouTube playlist URL"
        }
        setup()
        return flatItems(url.trim()).take(500)
    }

    private fun flatItems(input: String): List<Map<String, Any>> {
        val request = YoutubeDLRequest(input)
        request.addOption("--flat-playlist")
        request.addOption("--dump-single-json")
        request.addOption("--skip-download")
        request.addOption("--ignore-errors")
        val response = YoutubeDL.getInstance().execute(request)
        val parsed = JSONObject(response.out)
        val entries = parsed.optJSONArray("entries") ?: return emptyList()
        val results = ArrayList<Map<String, Any>>(entries.length())
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (!Regex("[A-Za-z0-9_-]{11}").matches(id)) continue
            val title = item.optString("title").ifBlank { "Unknown track" }
            val artist = item.optString("artist").ifBlank {
                item.optString("channel").ifBlank { item.optString("uploader") }
            }
            results.add(mapOf(
                "id" to id,
                "title" to title,
                "artist" to artist,
                "thumbnail" to "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                "duration" to item.optInt("duration", 0),
                "isCover" to Regex("(?i)cover|歌ってみた|커버|弾いてみた|acoustic cover").containsMatchIn(title)
            ))
        }
        return results
    }

    private fun resolve(id: String): Map<String, Any> {
        setup()
        val request = YoutubeDLRequest(videoUrl(id))
        request.addOption("--no-playlist")
        request.addOption("-f", "bestaudio[ext=m4a]/bestaudio")
        val info = YoutubeDL.getInstance().getInfo(request)
        val url = info.url ?: throw IllegalStateException("No playable audio URL")
        require(url.startsWith("https://") || url.startsWith("http://")) { "Unexpected stream URL" }
        return mapOf("url" to url, "id" to id)
    }

    private fun downloadMp3(id: String): Map<String, Any> {
        setup(ffmpeg = true)
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "Odify")
        check(directory.exists() || directory.mkdirs()) { "Cannot create music folder" }
        val finalFile = File(directory, "$id.mp3")
        if (!finalFile.isFile || finalFile.length() == 0L) {
            val request = YoutubeDLRequest(videoUrl(id))
            request.addOption("--no-playlist")
            request.addOption("-f", "bestaudio/best")
            request.addOption("-x")
            request.addOption("--audio-format", "mp3")
            request.addOption("--audio-quality", "2")
            request.addOption("--add-metadata")
            request.addOption("--no-overwrites")
            request.addOption("-o", File(directory, "$id.%(ext)s").absolutePath)
            YoutubeDL.getInstance().execute(request)
        }
        check(finalFile.isFile && finalFile.length() > 0L) { "MP3 output missing" }
        return mapOf("path" to finalFile.absolutePath, "videoId" to id, "bytes" to finalFile.length())
    }
}
