package com.zarz.spotiflac

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import xyz.luan.audioplayers.source.OdifyStreamRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

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
    private val updateLock = Any()
    @Volatile private var preferredAudioClient: String? = null
    private data class CachedAudio(val createdMs: Long, val data: Map<String, Any>)
    private val recentAudio = ConcurrentHashMap<String, CachedAudio>()
    // Foreground playback joins an in-flight warmup instead of spawning a
    // second Python process. Ownership belongs to the engine, not a UI waiter.
    private val failedClients = ConcurrentHashMap<String, Long>()
    private val resolveBudgetMs = 22_000L
    private val attemptBudgetMs = 8_000L
    private val audioRequests = OdifyAudioRequests<Map<String, Any>>(scope, resolveBudgetMs)
    private fun prioritizedClients(): List<String> {
        val saved = preferredAudioClient ?:
            context.getSharedPreferences("odify_extractor", Context.MODE_PRIVATE)
                .getString("working_client", null)
        val ordered = if (saved in audioClients) listOf(saved!!) +
            audioClients.filter { it != saved } else audioClients
        val now = SystemClock.elapsedRealtime()
        val available = ordered.filter { client ->
            val failedAt = failedClients[client]
            failedAt == null || now - failedAt >= 60_000L
        }
        return available.ifEmpty { ordered }
    }
    private fun cacheSuccess(id: String, client: String, data: Map<String, Any>) {
        preferredAudioClient = client
        context.getSharedPreferences("odify_extractor", Context.MODE_PRIVATE)
            .edit().putString("working_client", client).apply()
        recentAudio[id] = CachedAudio(SystemClock.elapsedRealtime(), data)
        // Evict one oldest entry; clearing everything causes repeated cold
        // extraction when the ninth song is queued.
        if (recentAudio.size > 8) {
            recentAudio.entries.minByOrNull { it.value.createdMs }?.let {
                recentAudio.remove(it.key, it.value)
            }
        }
    }
    private suspend fun prefetchAudio(id: String): Boolean {
        videoUrl(id)
        try {
            resolve(id)
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.i("OdifyExtractor",
                "Prefetch skipped category=${failureCategory(error)}")
            return false
        }
    }

    private fun expireCachedSource(id: String): Boolean {
        videoUrl(id)
        recentAudio.remove(id)?.data?.get("client")?.toString()?.let {
            // A successful 1 KiB probe does not guarantee MediaPlayer can
            // keep the connection open. Try a different client on recovery.
            failedClients[it] = SystemClock.elapsedRealtime()
        }
        audioRequests.invalidate(id)
        preferredAudioClient = null
        context.getSharedPreferences("odify_extractor", Context.MODE_PRIVATE)
            .edit().remove("working_client").apply()
        return true
    }

    private fun cachedAudio(id: String): Map<String, Any>? {
        val entry = recentAudio[id] ?: return null
        val handle = entry.data["nativeSource"] as? String ?: return null
        val signed = entry.data["url"]?.toString() ?: return null
        val expires = try {
            java.net.URI(signed).rawQuery?.split('&')
                ?.firstOrNull { it.startsWith("expire=") }
                ?.substringAfter("expire=")?.toLongOrNull()
        } catch (_: Exception) { null }
        if (expires != null && expires <= System.currentTimeMillis() / 1000L + 90L) {
            recentAudio.remove(id)
            return null
        }
        val ageMs = SystemClock.elapsedRealtime() - entry.createdMs
        return if (ageMs in 0..299_999L && OdifyStreamRegistry.lookup(handle) != null)
            entry.data else { recentAudio.remove(id); null }
    }

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
                    "diagnose" -> diagnose(arguments["videoId"] as? String ?: "")
                    "expire" -> expireCachedSource(arguments["videoId"] as? String ?: "")
                    "prefetch" -> prefetchAudio(arguments["videoId"] as? String ?: "")
                    else -> throw IllegalArgumentException("Unknown command: ${call.method}")
                }
                withContext(Dispatchers.Main) { result.success(value) }
            } catch (error: CancellationException) {
                // Expiring shared extraction must complete old Flutter calls.
                // Engine detach still propagates structured cancellation.
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.Main) {
                    result.error("odify_yt_dlp_CANCELLED", "재생 준비가 취소되었습니다.", null)
                }
            } catch (error: Exception) {
                // yt-dlp exceptions can contain hundreds of retry warnings.
                // Never dump verbose native traces into a full-screen dialog.
                val code = failureCategory(error)
                android.util.Log.e("OdifyExtractor",
                    "Native ${call.method} failed category=$code type=${error.javaClass.simpleName}")
                val userMessage = when (call.method) {
                    "resolve" -> "스트리밍 주소를 찾지 못했습니다. 잠시 후 다시 시도하거나 다른 곡을 선택하세요."
                    "downloadMp3" -> "MP3를 저장하지 못했습니다. YouTube 추출기 또는 영상 접근 제한을 확인해주세요."
                    else -> "YouTube 음악 정보를 가져오지 못했습니다. 다시 시도해주세요."
                }
                withContext(Dispatchers.Main) {
                    result.error("odify_yt_dlp_$code", "$userMessage [$code]",
                        mapOf("failureCode" to code, "operation" to call.method))
                }
            } catch (error: LinkageError) {
                // ExceptionInInitializerError and NoClassDefFoundError extend
                // LinkageError, NOT Exception. A broken youtubedl-android ZIP
                // registry must not terminate the entire Flutter process.
                // Never log the full exception: it may include signed URLs.
                android.util.Log.e("OdifyExtractor",
                    "Native runtime linkage failure: ${error.javaClass.simpleName}")
                withContext(Dispatchers.Main) {
                    result.error("odify_native_runtime_failure",
                        "음원 실행 환경 초기화에 실패했습니다. 앱 빌드를 업데이트해주세요.",
                        mapOf("failureCode" to "NATIVE_RUNTIME_INIT_FAILURE",
                              "operation" to call.method))
                }
            }
        }
    }

    private val audioClients = listOf("android", "web_embedded", "android_vr", "visionos")

    // Classify the native failure without leaking signed Googlevideo URLs,
    // headers, cookies or video IDs to user-visible diagnostics.
    private fun failureCategory(error: Throwable): String {
        val message = generateSequence(error) { it.cause }
            .take(4).joinToString(" ") { it.message.orEmpty() }.lowercase()
        return when {
            "js_runtime_missing" in message -> "JS_RUNTIME_MISSING"
            "js_challenge" in message -> "JS_CHALLENGE"
            "youtube_verification" in message -> "YOUTUBE_VERIFICATION"
            "cdn_403" in message -> "CDN_403"
            "rate_limit" in message -> "RATE_LIMIT"
            "ffmpeg_failed" in message -> "FFMPEG_FAILED"
            "format_unavailable" in message -> "FORMAT_UNAVAILABLE"
            "network_timeout" in message -> "NETWORK_TIMEOUT"
            "download_failed" in message -> "DOWNLOAD_FAILED"
            "no supported javascript runtime" in message ||
                "no javascript runtime" in message -> "JS_RUNTIME_MISSING"
            "ejs" in message || "signature solving" in message ||
                "nsig" in message -> "JS_CHALLENGE"
            "sign in to confirm" in message || "not a bot" in message ->
                "YOUTUBE_VERIFICATION"
            "http error 403" in message || "audio http 403" in message -> "CDN_403"
            "http error 429" in message || "audio http 429" in message -> "RATE_LIMIT"
            "ffmpeg" in message || "libffmpeg" in message -> "FFMPEG_FAILED"
            "requested format is not available" in message ||
                "no available audio format" in message -> "FORMAT_UNAVAILABLE"
            "timed out" in message || "timeout" in message -> "NETWORK_TIMEOUT"
            "unable to download" in message -> "DOWNLOAD_FAILED"
            else -> "EXTRACTOR_UNKNOWN"
        }
    }

    private suspend fun diagnose(id: String): Map<String, Any> {
        videoUrl(id)
        val libraryDir = File(context.applicationInfo.nativeLibraryDir)
        val report = linkedMapOf<String, Any>(
            "jsRuntimeBundled" to File(libraryDir, "libqjs.so").isFile,
            "pythonRuntimeBundled" to File(libraryDir, "libpython.so").isFile,
            "ffmpegRuntimeBundled" to File(libraryDir, "libffmpeg.so").isFile
        )
        try {
            setup(ffmpeg = true)
            report["ytDlpInitialized"] = true
            report["ffmpegInitialized"] = true
        } catch (error: Exception) {
            report["ytDlpInitialized"] = false
            report["failure"] = failureCategory(error)
            return report
        }
        val attempts = mutableListOf<String>()
        for (client in audioClients) {
            try {
                val validated = withTimeout(attemptBudgetMs) { extractAudio(id, client) }
                report["playableCdnProbe"] = true
                report["client"] = client
                report["extractMs"] = validated["extractMs"] ?: -1
                report["cdnProbeMs"] = validated["probeMs"] ?: -1
                report["audioCodec"] = validated["audioCodec"] ?: "unknown"
                report["audioContainer"] = validated["audioContainer"] ?: "unknown"
                report["attempts"] = attempts
                // A successful 1 KiB CDN probe does NOT validate Android's
                // media demuxer. Test only prepareAsync (never start audio).
                // Use the exact URL/header session that real playback uses.
                val handle = validated["nativeSource"]?.toString().orEmpty()
                val session = OdifyStreamRegistry.lookup(handle)
                report.putAll(if (session == null) {
                    mapOf("androidPrepare" to "SESSION_UNAVAILABLE")
                } else {
                    probeAndroidMediaPlayer(session)
                })
                return report
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                attempts.add("$client:${failureCategory(error)}")
            }
        }
        report["playableCdnProbe"] = false
        report["attempts"] = attempts
        return report
    }

    /**
     * Diagnostic only: MediaPlayer's prepareAsync without starting playback,
     * changing the current queue, or requesting audio focus. This detects
     * Android format/HTTP opening problems that a 1 KiB CDN GET cannot see.
     * Never include signed URLs, credentials, headers or video IDs in reports.
     */
    private suspend fun probeAndroidMediaPlayer(
        session: OdifyStreamRegistry.Stream
    ): Map<String, Any> = withContext(Dispatchers.Main) {
        val player = MediaPlayer()
        val readiness = CompletableDeferred<Map<String, Any>>()
        try {
            player.setOnPreparedListener {
                if (!readiness.isCompleted) {
                    readiness.complete(mapOf("androidPrepare" to "READY"))
                }
            }
            player.setOnErrorListener { _, what, extra ->
                if (!readiness.isCompleted) {
                    readiness.complete(mapOf(
                        "androidPrepare" to "MEDIA_ERROR",
                        "androidErrorWhat" to what,
                        "androidErrorExtra" to extra
                    ))
                }
                true
            }
            player.setDataSource(session.context, Uri.parse(session.url), session.headers)
            player.prepareAsync()
            withTimeoutOrNull(7000L) { readiness.await() }
                ?: mapOf("androidPrepare" to "TIMEOUT")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mapOf("androidPrepare" to "EXCEPTION",
                  "androidException" to error.javaClass.simpleName)
        } finally {
            player.setOnPreparedListener(null)
            player.setOnErrorListener(null)
            player.release()
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
            val channel = item.optString("channel").takeUnless { it.isBlank() || it == "null" }
                ?: item.optString("uploader").takeUnless { it.isBlank() || it == "null" } ?: ""
            val musicArtist = item.optString("artist").takeUnless { it.isBlank() || it == "null" }
                ?: item.optString("creator").takeUnless { it.isBlank() || it == "null" } ?: ""
            results.add(mapOf(
                "id" to id,
                "title" to title,
                "artist" to musicArtist.ifBlank { channel },
                "musicArtist" to musicArtist,
                "channel" to channel,
                "thumbnail" to "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                "duration" to item.optInt("duration", 0),
                "isCover" to Regex("(?i)cover|歌ってみた|커버|弾いてみた|acoustic cover").containsMatchIn(title)
            ))
        }
        return results
    }

    /**
     * yt-dlp's 0.16.0 embedded extractor predates modern YouTube player
     * responses. Do not prompt for a vendor APK; refresh only the extractor,
     * rate limited to once per 24 hours. Network/updates are optional and
     * never block the initial Home screen or local playback.
     */
    private fun refreshExtractorOnce(): Boolean = synchronized(updateLock) {
        val prefs = context.getSharedPreferences("odify_extractor", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_attempt_ms", 0)
        if (last > 0 && now - last in 0 until 86_400_000L) return@synchronized false
        prefs.edit().putLong("last_attempt_ms", now).apply()
        try {
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.NIGHTLY)
            true
        } catch (error: Exception) {
            android.util.Log.w("OdifyExtractor", "yt-dlp update unavailable: ${error.javaClass.simpleName}")
            false
        }
    }

    // A YouTube format URL alone is not necessarily playable: yt-dlp
    // supplies user-agent/referer headers that Android MediaPlayer drops.
    // Verify the first range against the CDN before handing it to Flutter.
    private fun validateAudioRange(url: String, headers: Map<String, String>) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 3500
            connection.readTimeout = 3500
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Range", "bytes=0-1023")
            connection.setRequestProperty("Accept-Encoding", "identity")
            for ((name, value) in headers) {
                if (name.lowercase() in setOf("user-agent", "referer",
                        "origin", "accept", "accept-language")) {
                    connection.setRequestProperty(name, value)
                }
            }
            val status = connection.responseCode
            if (status != 200 && status != 206) {
                throw IllegalStateException("CDN audio HTTP $status")
            }
            val mime = (connection.contentType ?: "").lowercase()
            if (mime.startsWith("image/") || mime.startsWith("text/") ||
                mime.contains("json")) {
                throw IllegalStateException("CDN returned non-audio content")
            }
            connection.inputStream.use {
                if (it.read() < 0) throw IllegalStateException("Empty audio source")
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun extractAudio(id: String, client: String): Map<String, Any> {
        val request = YoutubeDLRequest(videoUrl(id))
        request.addOption("--no-playlist")
        request.addOption("--no-warnings")
        request.addOption("--socket-timeout", "5")
        request.addOption("--retries", "0")
        request.addOption("--extractor-retries", "0")
        request.addOption("--skip-download")
        request.addOption("--dump-single-json")
        // Prefer AAC/M4A, which Android MediaPlayer plays more reliably
        // than Opus/WebM. Keep an audio-only fallback when AAC is missing.
        request.addOption("--extractor-args", "youtube:player_client=$client")
        request.addOption("-f", "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio")
        val extractedAt = SystemClock.elapsedRealtime()
        val processId = "odify-stream-${UUID.randomUUID()}"
        val response = try {
            // execute() waits on interruptible joins. runInterruptible makes
            // withTimeout stop the real subprocess, not just the Dart spinner.
            runInterruptible(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(request, processId, null)
            }
        } finally {
            YoutubeDL.getInstance().destroyProcessById(processId)
        }
        val extractMs = SystemClock.elapsedRealtime() - extractedAt
        val info = JSONObject(response.out.trim())
        val url = info.optString("url")
        val protocol = info.optString("protocol")
        val vcodec = info.optString("vcodec")
        require(url.startsWith("https://") && url.contains("googlevideo.com")) {
            "No direct playable CDN audio URL"
        }
        require(protocol.isBlank() || protocol == "https") {
            "Non-progressive audio protocol not supported"
        }
        require(vcodec.isBlank() || vcodec == "none") {
            "Selected format unexpectedly contains video"
        }
        val rawHeaders = info.optJSONObject("http_headers")
        val headers = mutableMapOf<String, String>()
        if (rawHeaders != null) {
            for (key in rawHeaders.keys()) {
                val value = rawHeaders.optString(key)
                if (value.isNotBlank() && !value.contains('\r') && !value.contains('\n')) {
                    headers[key] = value
                }
            }
        }
        val probeAt = SystemClock.elapsedRealtime()
        runInterruptible(Dispatchers.IO) { validateAudioRange(url, headers) }
        val probeMs = SystemClock.elapsedRealtime() - probeAt
        // The Mornye audioplayers implementation normally calls the
        // headerless MediaPlayer.setDataSource(String). A short-lived opaque
        // registry handle lets the pinned native player use the overload
        // accepting yt-dlp headers, with no Dart loopback hop.
        val nativeSource = OdifyStreamRegistry.register(context, url, headers, info.optString("ext"))
        val audioCodec = info.optString("acodec")
            .takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,40}")) } ?: "unknown"
        val audioContainer = info.optString("ext")
            .takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,20}")) } ?: "unknown"
        android.util.Log.i("OdifyExtractor",
            "Validated audio source for native playback; protocol=$protocol " +
            "format=${info.optString("ext")} codec=${info.optString("acodec")} " +
            "headerCount=${headers.size} extractMs=$extractMs probeMs=$probeMs")
        return mapOf(
            "url" to url, "headers" to headers, "id" to id,
            "nativeSource" to nativeSource, "extractMs" to extractMs,
            "probeMs" to probeMs, "client" to client,
            "audioCodec" to audioCodec, "audioContainer" to audioContainer
        )
    }

    private suspend fun resolve(id: String): Map<String, Any> {
        videoUrl(id)
        cachedAudio(id)?.let { return it }
        return audioRequests.get(id) { resolveFresh(id) }
    }

    private suspend fun resolveFresh(id: String): Map<String, Any> {
        cachedAudio(id)?.let { return it }
        val start = SystemClock.elapsedRealtime()
        setup()
        var failure: Exception? = null
        // A successful client is reused first. Do not run NIGHTLY updates
        // synchronously on the tap-to-play critical path.
        for (client in prioritizedClients()) {
            try {
                val result = withTimeout(attemptBudgetMs) { extractAudio(id, client) }
                cacheSuccess(id, client, result)
                android.util.Log.i("OdifyExtractor",
                    "Resolved elapsedMs=${SystemClock.elapsedRealtime() - start} client=$client")
                return result
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failedClients[client] = SystemClock.elapsedRealtime()
                failure = error
                android.util.Log.w("OdifyExtractor",
                    "Client=$client failure=${failureCategory(error)} elapsedMs=${SystemClock.elapsedRealtime() - start}")
            }
        }
        throw IllegalStateException("Audio resolution failed: ${failure?.let { failureCategory(it) } ?: "NO_CLIENT"}")
    }

    private fun downloadMp3(id: String): Map<String, Any> {
        setup(ffmpeg = true)
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "Odify")
        check(directory.exists() || directory.mkdirs()) { "Cannot create music folder" }
        val finalFile = File(directory, "$id.mp3")
        if (finalFile.isFile && finalFile.length() > 0L) {
            return mapOf("path" to finalFile.absolutePath, "videoId" to id,
                "bytes" to finalFile.length())
        }
        var failure: Exception? = null
        // Keep extraction updates out of the user-facing download operation;
        // avoid running all clients twice if a GitHub NIGHTLY check is slow.
        for (client in prioritizedClients()) {
            val startMs = SystemClock.elapsedRealtime()
            try {
                val request = YoutubeDLRequest(videoUrl(id))
                request.addOption("--no-playlist")
                request.addOption("--no-warnings")
                request.addOption("--socket-timeout", "9")
                request.addOption("--retries", "1")
                request.addOption("--extractor-retries", "1")
                request.addOption("--extractor-args", "youtube:player_client=$client")
                request.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", "2")
                request.addOption("--add-metadata")
                request.addOption("--no-overwrites")
                request.addOption("-o", File(directory, "$id.%(ext)s").absolutePath)
                YoutubeDL.getInstance().execute(request)
                if (finalFile.isFile && finalFile.length() > 0L) {
                    android.util.Log.i("OdifyExtractor",
                        "MP3 ready client=$client elapsedMs=${SystemClock.elapsedRealtime() - startMs}")
                    preferredAudioClient = client
                    context.getSharedPreferences("odify_extractor", Context.MODE_PRIVATE)
                        .edit().putString("working_client", client).apply()
                    return mapOf("path" to finalFile.absolutePath, "videoId" to id,
                        "bytes" to finalFile.length())
                }
                failure = IllegalStateException("No MP3 output after converter")
            } catch (error: Exception) {
                failure = error
                android.util.Log.w("OdifyExtractor",
                    "MP3 failed client=$client category=${failureCategory(error)} elapsedMs=${SystemClock.elapsedRealtime() - startMs}")
            }
        }
        android.util.Log.e("OdifyExtractor",
            "Download unavailable category=${failure?.let { failureCategory(it) } ?: "UNKNOWN"}")
        throw IllegalStateException(
            "Download failed: ${failure?.let { failureCategory(it) } ?: "UNKNOWN"}")
    }

}

