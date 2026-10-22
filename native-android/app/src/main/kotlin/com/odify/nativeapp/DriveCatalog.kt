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

private const val MUSIC_ROOT_ID = "1rNYGC6P2TK9RpokkI--0jRfrmbLTteNW"
private const val FOLDER_MIME = "application/vnd.google-apps.folder"

/** A SQLite index: deliberately NOT 50k rows of JSON in SharedPreferences. */
class DriveCatalog(private val context: Context) {
    private val prefs = context.getSharedPreferences("odify_drive_catalog_v1", 0)
    private val db = object : SQLiteOpenHelper(context, "odify_drive_catalog.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE tracks (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, title TEXT NOT NULL,
                artist TEXT NOT NULL DEFAULT '', album TEXT NOT NULL DEFAULT '',
                genre TEXT NOT NULL DEFAULT '', mime TEXT NOT NULL,
                modified TEXT NOT NULL DEFAULT '', cover TEXT NOT NULL DEFAULT '',
                lyrics TEXT NOT NULL DEFAULT ''
            )""")
            db.execSQL("CREATE INDEX tracks_search ON tracks(title, artist, genre)")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, newer: Int) {}
    }
    val folderId: String get() = prefs.getString("folder", MUSIC_ROOT_ID) ?: MUSIC_ROOT_ID
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
            put("album", album.trim())
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
        queue.add(folderId to "")
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
                val filename = file.getString("name").substringBeforeLast('.')
                val split = filename.split(" - ", limit = 2)
                val genre = file.optString("odifyGenre")
                val existing = dbConn.rawQuery(
                    "SELECT title,artist,album,cover,modified FROM tracks WHERE id=?",
                    arrayOf(id)
                ).use { c ->
                    if (c.moveToFirst()) (0..4).map { c.getString(it) } else null
                }
                val modified = file.optString("modifiedTime")
                val cv = ContentValues().apply {
                    put("id", id)
                    put("name", file.getString("name"))
                    put("title", if (existing == null) split.last() else existing[0])
                    put("artist", if (existing == null && split.size == 2) split[0]
                        else existing?.get(1) ?: "")
                    put("album", existing?.get(2) ?: "")
                    put("genre", genre)
                    put("mime", file.optString("mimeType"))
                    put("modified", modified)
                    put("cover", if (existing?.get(4) == modified) existing[3] else "")
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

    /** Album art is embedded in MP3 ID3v2 frames; pull ONLY first up to 1MiB. */
    suspend fun ensureCover(song: OdifySong): Boolean = withContext(Dispatchers.IO) {
        val driveId = song.id.removePrefix("gdrive:")
        if (driveId == song.id || !Regex("[A-Za-z0-9_-]{10,150}").matches(driveId)) {
            return@withContext false
        }
        val coverDir = File(context.filesDir, "drive_art").apply { mkdirs() }
        val target = File(coverDir, "$driveId.jpg")
        if (target.isFile && target.length() > 0) return@withContext true
        val url = "https://www.googleapis.com/drive/v3/files/$driveId?alt=media"
        val bytes = try { getRange(url, 1024 * 1024) } catch (_: Exception) { return@withContext false }
        val picture = findApic(bytes) ?: return@withContext false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(picture, 0, picture.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext false
        var sample = 1
        while (bounds.outWidth / sample > 768 || bounds.outHeight / sample > 768) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(
            picture, 0, picture.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        ) ?: return@withContext false
        try {
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } finally { bitmap.recycle() }
        val cv = ContentValues().apply { put("cover", target.absolutePath) }
        db.writableDatabase.update("tracks", cv, "id=?", arrayOf(driveId))
        true
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
    private fun getRange(uri: String, size: Int): ByteArray {
        val conn = URL(uri).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 12000; conn.readTimeout = 18000
            conn.setRequestProperty("Authorization", "Bearer ${DriveAuth.tokenBlocking(context)}")
            conn.setRequestProperty("Range", "bytes=0-${size-1}")
            if (conn.responseCode != 206) return byteArrayOf() // Do not load full MP3
            return conn.inputStream.use { input ->
                val data = ByteArray(size)
                var total = 0
                while (total < size) {
                    val n = input.read(data, total, size - total)
                    if (n <= 0) break
                    total += n
                }
                data.copyOf(total)
            }
        } finally { conn.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun findApic(bytes: ByteArray): ByteArray? {
        if (bytes.size < 10 || String(bytes, 0, 3, Charsets.ISO_8859_1) != "ID3") return null
        val version = bytes[3].toInt() and 0xff
        if (version !in 3..4) return null
        fun synchsafe(pos: Int): Int {
            var num = 0
            for (i in pos until pos + 4) num = (num shl 7) or (bytes[i].toInt() and 127)
            return num
        }
        val total = (10 + synchsafe(6)).coerceAtMost(bytes.size)
        var off = 10
        while (off + 10 <= total) {
            val name = String(bytes, off, 4, Charsets.ISO_8859_1)
            if (!name.all { it.isLetterOrDigit() }) break
            val len = if (version == 4) synchsafe(off + 4)
                else ByteBuffer.wrap(bytes, off + 4, 4).order(ByteOrder.BIG_ENDIAN).int
            if (len <= 0 || off + 10 + len > total) break
            if (name == "APIC") {
                val start = off + 10
                var p = start + 1 // encoding
                while (p < start + len && bytes[p] != 0.toByte()) p++ // mime
                p += 2 // terminator + picture type
                if (p >= start + len) break
                val encoding = bytes[start].toInt() and 255
                val sep = if (encoding in 1..2) 2 else 1
                while (p + sep <= start + len) {
                    if (bytes[p] == 0.toByte() && (sep == 1 ||
                        bytes[p + 1] == 0.toByte())) { p += sep; break }
                    p += sep
                }
                if (p < start + len) return bytes.copyOfRange(p, start + len)
            }
            off += 10 + len
        }
        return null
    }
}
