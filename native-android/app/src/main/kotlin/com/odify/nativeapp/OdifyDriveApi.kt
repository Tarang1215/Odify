package com.odify.nativeapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object OdifyDriveAuth {
    private val scope = Scope("https://www.googleapis.com/auth/drive.readonly")
    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(scope)).build()
    @Volatile private var accessToken: String? = null
    @Volatile private var issued: Long = 0L

    fun authorize(activity: Activity, done: (AuthorizationResult?, Exception?) -> Unit) {
        Identity.getAuthorizationClient(activity).authorize(request())
            .addOnSuccessListener { result ->
                if (!result.hasResolution()) accept(result)
                done(result, null)
            }.addOnFailureListener { done(null, it as? Exception ?: Exception(it)) }
    }

    fun finish(context: Context, data: Intent?) {
        accept(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data))
    }

    private fun accept(result: AuthorizationResult) {
        accessToken = result.accessToken ?: throw IOException("Missing Google Drive token")
        issued = System.currentTimeMillis()
    }

    /** Token is never stored on disk, sent to Compose, or embedded in a URL. */
    fun token(context: Context): String {
        val old = accessToken
        if (old != null && System.currentTimeMillis() - issued < 48 * 60_000L) return old
        val result = Tasks.await(Identity.getAuthorizationClient(context).authorize(request()),
            35, TimeUnit.SECONDS)
        if (result.hasResolution()) throw IOException("Reconnect Google Drive in Profile")
        accept(result)
        return accessToken ?: throw IOException("Drive authorization required")
    }

    fun clear() { accessToken = null; issued = 0L }
}

data class OdifyDriveFile(
    val id: String, val name: String, val mime: String,
    val modified: String = ""
) {
    val folder get() = mime == "application/vnd.google-apps.folder"
    val music get() = mime.startsWith("audio/") ||
        name.substringAfterLast('.', "").lowercase() in setOf(
            "mp3", "m4a", "flac", "aac", "wav", "ogg", "opus")
    val lyrics get() = name.endsWith(".lrc", true) || name.endsWith(".txt", true)
}

object OdifyDriveApi {
    private const val BASE = "https://www.googleapis.com/drive/v3/files"
    private val validId = Regex("[A-Za-z0-9_-]{4,256}")
    fun mediaUrl(id: String): String {
        require(validId.matches(id)) { "Invalid Drive ID" }
        return "$BASE/$id?alt=media"
    }

    private fun connect(context: Context, url: String, range: String? = null): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 25000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer ${OdifyDriveAuth.token(context)}")
        if (range != null) connection.setRequestProperty("Range", range)
        return connection
    }

    fun fetch(context: Context, url: String, limit: Int = 2_000_000,
              range: String? = null): ByteArray {
        for (attempt in 0..1) {
            val conn = connect(context, url, range)
            try {
                val code = conn.responseCode
                if (code == 401 && attempt == 0) {
                    OdifyDriveAuth.clear()
                    continue
                }
                if (code !in 200..299) throw IOException("Drive HTTP $code")
                return conn.inputStream.use { stream ->
                    val result = ByteArrayOutputStream()
                    val chunk = ByteArray(16384)
                    while (true) {
                        val n = stream.read(chunk)
                        if (n < 0) break
                        if (result.size() + n > limit) throw IOException("Response too large")
                        result.write(chunk, 0, n)
                    }
                    result.toByteArray()
                }
            } finally { conn.disconnect() }
        }
        throw IOException("Drive token expired")
    }

    fun list(context: Context, folderId: String): List<OdifyDriveFile> {
        require(folderId == "root" || validId.matches(folderId))
        val all = mutableListOf<OdifyDriveFile>()
        var page: String? = null
        do {
            val url = Uri.parse(BASE).buildUpon()
                .appendQueryParameter("q", "'$folderId' in parents and trashed=false")
                .appendQueryParameter("fields",
                    "nextPageToken,files(id,name,mimeType,modifiedTime)")
                .appendQueryParameter("pageSize", "1000")
                .apply { page?.let { appendQueryParameter("pageToken", it) } }
                .build().toString()
            val result = JSONObject(String(fetch(context, url)))
            val files = result.optJSONArray("files")
            if (files != null) for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                all.add(OdifyDriveFile(
                    f.getString("id"), f.getString("name"),
                    f.getString("mimeType"), f.optString("modifiedTime")
                ))
            }
            page = result.optString("nextPageToken").takeIf { it.isNotBlank() }
            require(all.size < 100_000) { "Drive scan safety limit reached" }
        } while (page != null)
        return all
    }

    fun scan(context: Context, root: String): List<Pair<OdifyDriveFile, String>> {
        val queue = ArrayDeque<Pair<String,String>>()
        queue.add(root to "")
        val visited = mutableSetOf<String>()
        val songs = mutableListOf<Pair<OdifyDriveFile,String>>()
        while (queue.isNotEmpty()) {
            val (folderId, genre) = queue.removeFirst()
            if (!visited.add(folderId)) continue
            require(visited.size < 2500) { "Too many music subfolders" }
            for (file in list(context, folderId)) {
                if (file.folder) queue.add(file.id to genre.ifBlank { file.name })
                else if (file.music || file.lyrics) songs.add(file to genre)
            }
        }
        return songs
    }

    fun firstBytes(context: Context, id: String, bytes: Int = 1_048_576): ByteArray =
        fetch(context, mediaUrl(id), bytes + 1024, "bytes=0-${bytes-1}")
}

class OdifyDriveSync(private val context: Context) {
    private val prefs = context.getSharedPreferences("odify_drive_v8", Context.MODE_PRIVATE)
    val folderId get() = prefs.getString("folderId", "") ?: ""
    val folderName get() = prefs.getString("folderName", "") ?: ""
    fun setFolder(id: String, name: String) {
        prefs.edit().putString("folderId", id).putString("folderName", name).apply()
    }

    suspend fun refresh(library: OdifyLibrary): Int = withContext(Dispatchers.IO) {
        require(folderId.isNotEmpty()) { "Select a Drive music folder first" }
        val all = OdifyDriveApi.scan(context, folderId)
        val lyricFiles = all.filter { it.first.lyrics }.associateBy {
            it.first.name.substringBeforeLast('.').lowercase()
        }
        val music = all.filter { it.first.music }.map { (entry, category) ->
            val name = entry.name.substringBeforeLast('.')
            val parts = name.split(" - ", limit = 2)
            val text = lyricFiles[name.lowercase()]?.first?.let {
                runCatching {
                    String(OdifyDriveApi.fetch(context,
                        OdifyDriveApi.mediaUrl(it.id), 200_000))
                }.getOrDefault("")
            } ?: ""
            OdifySong(
                id = "gdrive-${entry.id}", title = if (parts.size == 2) parts[1] else name,
                artist = if (parts.size == 2) parts[0] else "", album = "",
                filePath = "", sourceUri = "odifydrive://${entry.id}",
                genre = category, lyrics = text, driveVersion = entry.modified
            )
        }
        library.mergeDriveTracks(music)
        prefs.edit().putLong("lastSync", System.currentTimeMillis()).apply()
        music.size
    }
}
