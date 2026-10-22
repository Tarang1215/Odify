package com.odify.nativeapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.Locale

private const val DEFAULT_FOLDER_NAME = "Odify_Music_Test"
private const val FOLDER_MIME = "application/vnd.google-apps.folder"

/** A SQLite index: deliberately NOT 50k rows of JSON in SharedPreferences. */
class DriveCatalog(private val context: Context) {
    private val prefs = context.getSharedPreferences("odify_drive_catalog_v1", 0)
    private val db = object : SQLiteOpenHelper(context, "odify_drive_catalog.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE tracks (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, title TEXT NOT NULL,
                artist TEXT NOT NULL DEFAULT '', album TEXT NOT NULL DEFAULT '',
                genre TEXT NOT NULL DEFAULT '', mime TEXT NOT NULL,
                modified TEXT NOT NULL DEFAULT '', cover TEXT NOT NULL DEFAULT '',
                lyrics TEXT NOT NULL DEFAULT '',
                meta_source TEXT NOT NULL DEFAULT 'filename',
                meta_checked_modified TEXT NOT NULL DEFAULT ''
            )""")
            db.execSQL("CREATE INDEX tracks_search ON tracks(title, artist, genre)")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, newer: Int) {
            if (old < 2) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN meta_source TEXT NOT NULL DEFAULT 'filename'")
                db.execSQL("ALTER TABLE tracks ADD COLUMN meta_checked_modified TEXT NOT NULL DEFAULT ''")
            }
        }
    }
    val folderId: String get() = prefs.getString("folder", "") ?: ""
    var lastSync: Long
        get() = prefs.getLong("lastSync", 0)
        private set(v) { prefs.edit().putLong("lastSync", v).apply() }

    fun setFolder(id: String) {
        require(Regex("[A-Za-z0-9_-]{10,150}").matches(id)) { "유효한 Drive 폴더 ID가 아닙니다." }
        prefs.edit().putString("folder", id).apply()
        db.writableDatabase.delete("tracks", null, null)
        lastSync = 0
    }

    fun hideTrack(song: OdifySong) {
        val hidden = prefs.getStringSet("hidden", emptySet())?.toMutableSet()
            ?: mutableSetOf()
        hidden += song.id.removePrefix("gdrive:")
        prefs.edit().putStringSet("hidden", hidden).apply()
    }

    fun updateMetadata(song: OdifySong, title: String, artist: String, album: String) {
        val id = song.id.removePrefix("gdrive:")
        require(title.isNotBlank())
        val cv = ContentValues().apply {
            put("title", title.trim()); put("artist", artist.trim())
            put("album", album.trim()); put("meta_source", "user")
        }
        db.writableDatabase.update("tracks", cv, "id=?", arrayOf(id))
    }

    fun tracks(): List<OdifySong> {
        val result = ArrayList<OdifySong>()
        val hidden = prefs.getStringSet("hidden", emptySet()) ?: emptySet()
        db.readableDatabase.rawQuery(
            "SELECT id,name,title,artist,album,genre,cover FROM tracks ORDER BY modified DESC,name LIMIT 100000",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val fileId = cursor.getString(0)
                if (fileId in hidden) continue
                result += OdifySong(
                    id = "gdrive:$fileId", title = cursor.getString(2),
                    artist = cursor.getString(3), album = cursor.getString(4),
                    filePath = "", artworkPath = cursor.getString(6).takeIf { it.isNotBlank() },
                    sourceUri = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media",
                    genre = cursor.getString(5)
                )
            }
        }
        return result
    }

    /**
     * Browse only the user's selected music root, recursively.
     * Sync is transactional: unsuccessful network listing leaves old catalog.
     * Volume-limited to 50k files and 4k folders to prevent runaway scans.
     */
    suspend fun sync(onProgress: (Int) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        val queue = ArrayDeque<Pair<String,String>>()
        val initialFolder = folderId.ifBlank {
            // Never publish the user's private folder ID inside an open-source APK.
            // Locate the default folder by name in the authenticated account.
            val q = "mimeType = '$FOLDER_MIME' and name = '$DEFAULT_FOLDER_NAME' and trashed = false"
            val list = JSONObject(apiJson("files", mapOf(
                "q" to q, "pageSize" to "100",
                "fields" to "files(id,name),nextPageToken"
            ))).getJSONArray("files")
            if (list.length() == 0) error("Drive에서 $DEFAULT_FOLDER_NAME 폴더를 찾을 수 없습니다.")
            list.getJSONObject(0).getString("id")
        }
        queue.add(initialFolder to "")
        val collected = LinkedHashMap<String, JSONObject>()
        var visitedFolders = 0
        while (queue.isNotEmpty()) {
            val (parent, genrePath) = queue.removeFirst()
            if (++visitedFolders > 4_000) error("폴더 수 제한(4,000)을 초과했습니다.")
            var nextPage: String? = null
            do {
                val query = "'$parent' in parents and trashed = false"
                val params = linkedMapOf(
                    "q" to query,
                    "pageSize" to "1000",
                    "fields" to "nextPageToken,files(id,name,mimeType,modifiedTime,capabilities/canDownload)",
                    "pageToken" to (nextPage ?: "")
                )
                val json = JSONObject(apiJson("files", params))
                val files = json.getJSONArray("files")
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    val id = f.getString("id")
                    val name = f.getString("name")
                    val mime = f.optString("mimeType")
                    if (mime == FOLDER_MIME) {
                        queue.add(id to if (genrePath.isEmpty()) name else "$genrePath / $name")
                        continue
                    }
                    if (!isAudio(name, mime)) continue
                    if (f.optJSONObject("capabilities")?.optBoolean("canDownload", true) == false) {
                        continue
                    }
                    f.put("odifyGenre", genrePath)
                    collected[id] = f
                    if (collected.size > 50_000) error("음악 인덱스 제한(50,000곡)을 초과했습니다.")
                }
                onProgress(collected.size)
                nextPage = json.optString("nextPageToken").takeIf { it.isNotBlank() }
            } while (nextPage != null)
        }
        val dbConn = db.writableDatabase
        dbConn.beginTransaction()
        try {
            dbConn.execSQL("CREATE TEMP TABLE IF NOT EXISTS odify_seen (id TEXT PRIMARY KEY)")
            dbConn.delete("odify_seen", null, null)
            for ((id, file) in collected) {
                val (fallbackTitle, fallbackArtist) =
                    DriveTagReader.fallbackFromName(file.getString("name"))
                val genre = DriveTagReader.recognizableGenre(file.optString("odifyGenre"))
                val existing = dbConn.rawQuery(
                    "SELECT title,artist,album,cover,modified,meta_source,meta_checked_modified,lyrics,genre FROM tracks WHERE id=?",
                    arrayOf(id)
                ).use { c ->
                    if (c.moveToFirst()) (0..8).map { c.getString(it) } else null
                }
                val modified = file.optString("modifiedTime")
                val hasTags = existing?.get(5) in setOf("tag", "user")
                val cv = ContentValues().apply {
                    put("id", id)
                    put("name", file.getString("name"))
                    put("title", if (hasTags) existing!![0] else fallbackTitle)
                    put("artist", if (hasTags) existing!![1] else fallbackArtist)
                    put("album", if (hasTags) existing!![2] else "")
                    put("genre", if (hasTags && !existing!![8].isNullOrBlank()) existing[8] else genre)
                    put("mime", file.optString("mimeType"))
                    put("modified", modified)
                    put("cover", if (existing?.get(4) == modified) existing[3] else "")
                    put("meta_source", existing?.get(5) ?: "filename")
                    put("meta_checked_modified", if (existing?.get(4) == modified)
                        existing[6] else "")
                    put("lyrics", existing?.get(7) ?: "")
                }
                dbConn.insertWithOnConflict("tracks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                val seen = ContentValues().apply { put("id", id) }
                dbConn.insertWithOnConflict("odify_seen", null, seen, SQLiteDatabase.CONFLICT_IGNORE)
            }
            dbConn.execSQL("DELETE FROM tracks WHERE id NOT IN (SELECT id FROM odify_seen)")
            dbConn.setTransactionSuccessful()
        } finally { dbConn.endTransaction() }
        lastSync = System.currentTimeMillis()
        collected.size
    }

    /**
     * Read actual ID3/MP4 tags in bounded Drive byte ranges, never downloading
     * the entire song just to discover artwork, title or artist.
     * The catalog keeps a modified-time fingerprint to avoid redundant calls.
     */
    suspend fun prefetchMetadata(limit: Int = 16): Int = withContext(Dispatchers.IO) {
        val candidates = ArrayList<OdifySong>()
        db.readableDatabase.rawQuery(
            "SELECT id,name,title,artist,album FROM tracks " +
                "WHERE meta_checked_modified != modified " +
                "ORDER BY modified DESC LIMIT ${limit.coerceIn(1, 40)}",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                candidates += OdifySong(
                    id = "gdrive:$id", title = cursor.getString(2),
                    artist = cursor.getString(3), album = cursor.getString(4),
                    filePath = "",
                    sourceUri = "https://www.googleapis.com/drive/v3/files/$id?alt=media"
                )
            }
        }
        var changed = 0
        for (song in candidates) {
            if (ensureMetadata(song)) changed++
        }
        changed
    }

    /** Refresh title, artist, album, lyrics and cover for a single Drive file. */
    suspend fun ensureMetadata(song: OdifySong): Boolean = withContext(Dispatchers.IO) {
        if (!song.id.startsWith("gdrive:")) return@withContext false
        val driveId = song.id.removePrefix("gdrive:")
        if (!Regex("[A-Za-z0-9_-]{10,150}").matches(driveId)) return@withContext false
        val record = db.readableDatabase.rawQuery(
            "SELECT name,mime,modified,meta_checked_modified,meta_source FROM tracks WHERE id=?",
            arrayOf(driveId)
        ).use { c -> if (c.moveToFirst()) (0..4).map { c.getString(it) } else null }
            ?: return@withContext false
        val (name, mime, modified, checked, origin) = record
        if (modified == checked) return@withContext false
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val url = "https://www.googleapis.com/drive/v3/files/$driveId?alt=media"
        val bytes = try {
            if (ext == "m4a" || ext == "mp4" || mime == "audio/mp4") {
                // MP4 'moov/ilst' lives at the end for many encoders.
                val endBytes = getRange(url, 512 * 1024, suffix = true)
                val parsed = DriveTagReader.parse(endBytes, "m4a")
                if (parsed.hasAnyTag) endBytes else getRange(url, 512 * 1024)
            } else getRange(url, 1024 * 1024)
        } catch (_: Exception) { return@withContext false }
        if (bytes.isEmpty()) return@withContext false
        val tag = DriveTagReader.parse(bytes, ext)
        val coverPath = tag.coverBytes?.let { art ->
            if (art.size > 900_000) null else saveCover(driveId, art)
        }
        val hasText = listOf(tag.title, tag.artist, tag.album, tag.genre)
            .any { !it.isNullOrBlank() }
        val cv = ContentValues().apply {
            if (origin != "user") {
                tag.title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                tag.artist?.takeIf { it.isNotBlank() }?.let { put("artist", it) }
                tag.album?.takeIf { it.isNotBlank() }?.let { put("album", it) }
                if (hasText) put("meta_source", "tag")
            }
            tag.genre?.takeIf { it.isNotBlank() }?.let { put("genre", it) }
            tag.lyrics?.takeIf { it.isNotBlank() }?.let { put("lyrics", it) }
            coverPath?.let { put("cover", it) }
            put("meta_checked_modified", modified)
        }
        val updated = db.writableDatabase.update(
            "tracks", cv, "id=? AND modified=?", arrayOf(driveId, modified)
        )
        updated > 0 && (tag.hasAnyTag || coverPath != null)
    }

    private fun saveCover(driveId: String, bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 768 || bounds.outHeight / sample > 768) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        ) ?: return null
        val target = File(File(context.filesDir, "drive_art").apply { mkdirs() },
            "$driveId.jpg")
        return try {
            target.outputStream().use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 86, it))
                    error("이미지 저장 실패")
            }
            target.absolutePath
        } catch (_: Exception) { null }
        finally { bitmap.recycle() }
    }

    suspend fun lyrics(song: OdifySong): String = withContext(Dispatchers.IO) {
        val id = song.id.removePrefix("gdrive:")
        db.readableDatabase.rawQuery("SELECT lyrics FROM tracks WHERE id=?",
            arrayOf(id)).use {
            if (it.moveToFirst()) it.getString(0).orEmpty() else ""
        }
    }

    private fun isAudio(name: String, mime: String): Boolean {
        if (mime.startsWith("audio/")) return true
        return name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
            setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")
    }

    private fun apiJson(endpoint: String, params: Map<String,String>): String {
        val query = params.entries.filter { it.value.isNotEmpty() }.joinToString("&") {
            "${enc(it.key)}=${enc(it.value)}"
        }
        val conn = URL("https://www.googleapis.com/drive/v3/$endpoint?$query")
            .openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Authorization", "Bearer ${DriveAuth.tokenBlocking(context)}")
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            if (conn.responseCode != 200) {
                error("Drive 목록 조회 실패: HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }
    private fun getRange(uri: String, size: Int, suffix: Boolean = false): ByteArray {
        val conn = URL(uri).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 12_000
            conn.readTimeout = 18_000
            conn.setRequestProperty("Authorization", "Bearer ${DriveAuth.tokenBlocking(context)}")
            conn.setRequestProperty("Range",
                if (suffix) "bytes=-$size" else "bytes=0-${size-1}")
            if (conn.responseCode != 206) return byteArrayOf()
            return conn.inputStream.use { input ->
                val data = ByteArray(size)
                var total = 0
                while (total < size) {
                    val n = input.read(data, total, size-total)
                    if (n <= 0) break
                    total += n
                }
                data.copyOf(total)
            }
        } finally { conn.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

}
