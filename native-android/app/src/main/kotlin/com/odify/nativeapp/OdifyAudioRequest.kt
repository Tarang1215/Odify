package com.odify.nativeapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** Outbox is durable before consent/network access. Audio is never removed here. */
class OdifyAudioRequests(private val context: Context) {
    private val helper = object : SQLiteOpenHelper(context, "odify_audio_requests.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE requests(id TEXT PRIMARY KEY,song_id TEXT NOT NULL,body TEXT NOT NULL,remote_id TEXT NOT NULL DEFAULT '',created INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, newer: Int) = Unit
    }

    fun enqueue(song: OdifySong, kind: String, link: String, reason: String): String {
        require(kind in setOf("report", "paste_link"))
        val fileId = song.id.removePrefix("gdrive:").takeIf {
            song.id.startsWith("gdrive:") && Regex("[A-Za-z0-9_-]{10,150}").matches(it)
        }.orEmpty()
        require(kind != "paste_link" || fileId.isNotEmpty()) { "Drive에 연결된 곡에서 링크를 교체할 수 있습니다." }
        val canonical = if (kind == "paste_link") OdifyAudioRequestPolicy.youtubeUrl(link) else ""
        val id = UUID.randomUUID().toString()
        val body = JSONObject().put("schema", 1).put("request_id", id)
            .put("kind", kind).put("song_id", song.id).put("drive_file_id", fileId)
            .put("expected_md5", song.contentMd5).put("title", song.title)
            .put("artist", song.artist).put("album", song.album).put("duration_ms", song.durationMs)
            .put("youtube_url", canonical).put("reason", reason.trim().take(500))
            .put("created_at", java.time.Instant.now().toString()).put("status", "LOCAL_PENDING")
        helper.writableDatabase.insertOrThrow("requests", null, ContentValues().apply {
            put("id", id); put("song_id", song.id); put("body", body.toString())
            put("created", System.currentTimeMillis())
        })
        return id
    }

    fun latest(songId: String): JSONObject? = helper.readableDatabase.rawQuery(
        "SELECT body FROM requests WHERE song_id=? ORDER BY created DESC LIMIT 1", arrayOf(songId)
    ).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }

    suspend fun sync() = withContext(Dispatchers.IO) { syncLock.withLock {
        val requests = helper.readableDatabase.rawQuery(
            "SELECT id,body,remote_id FROM requests ORDER BY created DESC LIMIT 100", null
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) add(Triple(cursor.getString(0), JSONObject(cursor.getString(1)), cursor.getString(2)))
        } }
        if (requests.isEmpty()) return@withLock
        val token = DriveAuth.uploadTokenBlocking(context)
        var folder: String? = null
        for ((id, body, knownRemote) in requests) {
            var remoteId = knownRemote
            if (remoteId.isBlank()) {
                if (folder == null) folder = ensureFolder(token)
                // A lost POST response must not duplicate a request.
                val query = "'$folder' in parents and trashed=false and name='request-$id.json'"
                val found = http(token, "GET", "https://www.googleapis.com/drive/v3/files?q=${enc(query)}&fields=files(id)&pageSize=100").getJSONArray("files")
                remoteId = if (found.length() > 0) found.getJSONObject(0).getString("id") else {
                    val meta = JSONObject().put("name", "request-$id.json").put("mimeType", "application/json")
                        .put("parents", org.json.JSONArray().put(folder))
                    val boundary = "odify_$id"
                    val payload = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: application/json\r\n\r\n${JSONObject(body.toString()).put("status", "QUEUED")}\r\n--$boundary--\r\n"
                    http(token, "POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
                        payload, "multipart/related; boundary=$boundary").getString("id")
                }
                helper.writableDatabase.update("requests", ContentValues().apply { put("remote_id", remoteId) }, "id=?", arrayOf(id))
            }
            val result = http(token, "GET", "https://www.googleapis.com/drive/v3/files/$remoteId?alt=media")
            check(result.optString("request_id") == id) { "요청 응답이 일치하지 않습니다." }
            helper.writableDatabase.update("requests", ContentValues().apply { put("body", result.toString()) }, "id=?", arrayOf(id))
        }
    } }

    private fun ensureFolder(token: String): String {
        val q = "name='Odify_Audio_Requests' and mimeType='application/vnd.google-apps.folder' and trashed=false"
        val found = http(token, "GET", "https://www.googleapis.com/drive/v3/files?q=${enc(q)}&fields=files(id)&pageSize=100").getJSONArray("files")
        return if (found.length() > 0) found.getJSONObject(0).getString("id") else
            http(token, "POST", "https://www.googleapis.com/drive/v3/files?fields=id",
                JSONObject().put("name", "Odify_Audio_Requests").put("mimeType", "application/vnd.google-apps.folder").toString()).getString("id")
    }

    private fun http(token: String, method: String, url: String, payload: String? = null,
                     mime: String = "application/json; charset=UTF-8"): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method; conn.connectTimeout = 15000; conn.readTimeout = 30000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (payload != null) {
                val bytes = payload.toByteArray(Charsets.UTF_8)
                conn.doOutput = true; conn.setRequestProperty("Content-Type", mime)
                conn.setFixedLengthStreamingMode(bytes.size); conn.outputStream.use { it.write(bytes) }
            }
            check(conn.responseCode in 200..299) { "Drive 요청 전송 실패 (${conn.responseCode}). 요청은 휴대폰에 보관됩니다." }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
    }
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    companion object { private val syncLock = Mutex() }
}
