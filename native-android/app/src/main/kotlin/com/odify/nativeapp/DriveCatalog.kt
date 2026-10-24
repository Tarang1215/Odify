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
// Only these explicit Odify library roots are discovered. Never traverse the
// rest of the user's Drive. Both top-level and nested Auto_Upload are accepted.
internal val ODIFY_LIBRARY_ROOT_NAMES = listOf(
    DEFAULT_FOLDER_NAME, "odify_music_test",
    "Odify_Auto_Upload", "odify_auto_upload",
    "Odify_Cloud_Uploads"
)
private const val FOLDER_MIME = "application/vnd.google-apps.folder"

/** A SQLite index: deliberately NOT 50k rows of JSON in SharedPreferences. */
class DriveCatalog(private val context: Context) {
    private val prefs = context.getSharedPreferences("odify_drive_catalog_v1", 0)
    private val db = object : SQLiteOpenHelper(context, "odify_drive_catalog.db", null, 4) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE tracks (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, title TEXT NOT NULL,
                artist TEXT NOT NULL DEFAULT '', album TEXT NOT NULL DEFAULT '',
                genre TEXT NOT NULL DEFAULT '', mime TEXT NOT NULL,
                modified TEXT NOT NULL DEFAULT '', cover TEXT NOT NULL DEFAULT '',
                lyrics TEXT NOT NULL DEFAULT '',
                meta_source TEXT NOT NULL DEFAULT 'filename',
                meta_checked_modified TEXT NOT NULL DEFAULT '',
                md5 TEXT NOT NULL DEFAULT '',
                file_bytes INTEGER NOT NULL DEFAULT 0
            )""")
            db.execSQL("CREATE INDEX tracks_search ON tracks(title, artist, genre)")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, newer: Int) {
            if (old < 2) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN meta_source TEXT NOT NULL DEFAULT 'filename'")
                db.execSQL("ALTER TABLE tracks ADD COLUMN meta_checked_modified TEXT NOT NULL DEFAULT ''")
            }
            if (old < 3) {
                // Version 10 successfully read titles but some ID3 APIC frames
                // had a leading NUL. Rescan only the missing-art tracks once.
                db.execSQL("UPDATE tracks SET meta_checked_modified='' WHERE cover=''")
            }
            if (old < 4) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN md5 TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tracks ADD COLUMN file_bytes INTEGER NOT NULL DEFAULT 0")
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
        // Keep existing metadata and manual corrections intact until a
        // successful transactional sync replaces the visible file index.
        lastSync = 0
    }

    /** Manual cover replaces only Odify display art, never the original Drive audio. */
    suspend fun setManualArtwork(song: OdifySong, uri: android.net.Uri) =
        withContext(Dispatchers.IO) {
            require(song.id.startsWith("gdrive:")) { "Drive 곡만 이 메뉴에서 커버를 설정할 수 있습니다." }
            val fileId = song.id.removePrefix("gdrive:")
            require(Regex("[A-Za-z0-9_-]{10,150}").matches(fileId))
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth in 1..16000 && bounds.outHeight in 1..16000) {
                "올바른 JPG/PNG 사진을 선택해 주세요."
            }
            var sample = 1
            while (bounds.outWidth/sample > 768 || bounds.outHeight/sample > 768) sample *= 2
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null,
                    BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("사진을 열 수 없습니다.")
            val directory = File(context.filesDir, "manual_art").apply { mkdirs() }
            val target = File(directory, "$fileId-${System.nanoTime()}.jpg")
            try {
                target.outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it))
                }
            } finally { bitmap.recycle() }
            val previous = prefs.getString("manualArt:$fileId", null)
            prefs.edit().putString("manualArt:$fileId", target.absolutePath).apply()
            previous?.let { old ->
                val file = File(old)
                if (file.parentFile == directory) file.delete()
            }
        }

    /** Verified master video thumbnail is a display fallback, not original art. */
    fun setFallbackArtwork(fileId: String, picturePath: String) {
        if (!File(picturePath).isFile) return
        db.writableDatabase.execSQL(
            "UPDATE tracks SET cover=? WHERE id=? AND cover=''",
            arrayOf(picturePath, fileId)
        )
    }

    /** Exact-byte duplicate detection. Compare Drive's MD5 and file size. */
    fun hasExactBytes(md5Hex: String, size: Long): Boolean {
        require(Regex("[0-9a-fA-F]{32}").matches(md5Hex))
        return db.readableDatabase.rawQuery(
            "SELECT 1 FROM tracks WHERE md5=? AND file_bytes=? LIMIT 1",
            arrayOf(md5Hex.lowercase(Locale.ROOT), size.toString())
        ).use { it.moveToFirst() }
    }

    /** ODF identifiers in filenames are stronger than title-only guesses. */
    fun hasTrackCode(code: String): Boolean {
        if (!Regex("ODF[0-9]{6}", RegexOption.IGNORE_CASE).matches(code))
            return false
        return db.readableDatabase.rawQuery(
            "SELECT name FROM tracks WHERE UPPER(name) LIKE ?",
            arrayOf(code.uppercase(Locale.ROOT) + "%")
        ).use { cursor ->
            val prefix = code.uppercase(Locale.ROOT)
            while(cursor.moveToNext()) {
                val text = cursor.getString(0).uppercase(Locale.ROOT)
                if (text.length > prefix.length &&
                    text.substring(prefix.length, prefix.length + 1) in
                        listOf(".", " ", "-", "_")) return@use true
            }
            false
        }
    }

    /** Avoid materializing all artwork/lyrics when checking another upload. */
    fun hasMatchingSong(title: String, artist: String, album: String): Boolean {
        if (title.isBlank() || artist.isBlank() || album.isBlank()) return false
        val wanted = listOf(title, artist, album).map(MasterDuplicateDetector::norm)
        return db.readableDatabase.rawQuery(
            "SELECT title,artist,album FROM tracks", null
        ).use { cursor ->
            while(cursor.moveToNext()) {
                if (listOf(cursor.getString(0),cursor.getString(1),
                        cursor.getString(2)).map(MasterDuplicateDetector::norm) == wanted)
                    return@use true
            }
            false
        }
    }

    fun hideTrack(song: OdifySong) {
        val hidden = prefs.getStringSet("hidden", emptySet())?.toMutableSet()
            ?: mutableSetOf()
        hidden += song.id.removePrefix("gdrive:")
        prefs.edit().putStringSet("hidden", hidden).apply()
    }

    /**
     * MASTER Sheet is the canonical human-verified name source for files bearing
     * its ODF track code (or an exact Drive ID). Never edits the original audio
     * and never overwrites an explicit user correction.
     */
    fun applyMasterCorrections(master: List<MasterTrack>): Int {
        val conn=db.writableDatabase
        var updated=0
        conn.beginTransaction()
        try {
            for (track in master) {
                if (!Regex("ODF[0-9]{6}",RegexOption.IGNORE_CASE).matches(track.trackId)) continue
                if (track.title.isBlank() || track.artist.isBlank()) continue
                val searchId=track.driveId
                val rows=ArrayList<Pair<String,String>>()
                val prefix=track.trackId.uppercase(Locale.ROOT)
                conn.rawQuery(
                    "SELECT id,name,meta_source FROM tracks WHERE " +
                        "(UPPER(name) LIKE ? OR id=?)",
                    arrayOf(prefix + "%", searchId)
                ).use { c ->
                    while(c.moveToNext()) {
                        val id=c.getString(0)
                        val name=c.getString(1).uppercase(Locale.ROOT)
                        // ODF000001.mp3 and ODF000001 - artist - title.mp3 are
                        // both valid. Do not confuse ODF000001 with ODF0000010.
                        val suffix=name.getOrNull(prefix.length)
                        val codeMatches=name.startsWith(prefix) &&
                            suffix != null && suffix in charArrayOf('.', ' ', '-', '_')
                        if ((searchId.isNotBlank() && id==searchId) || codeMatches)
                            rows += id to c.getString(2)
                    }
                }
                for((id,origin) in rows) {
                    if(origin=="user") continue
                    val cv=ContentValues().apply {
                        put("title",track.title)
                        put("artist",track.artist)
                        put("album",track.album)
                        // Prefer the canonical MASTER genre over guessed folder
                        // paths, but never replace a known genre with 미분류.
                        if (track.genre.isNotBlank() && track.genre != "미분류")
                            put("genre", track.genre)
                        put("meta_source","master")
                    }
                    updated+=conn.update("tracks",cv,"id=? AND meta_source!=?",
                        arrayOf(id,"user"))
                }
            }
            conn.setTransactionSuccessful()
        } finally { conn.endTransaction() }
        return updated
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

    /** Original extension for device export; the user's title may omit it. */
    fun filename(fileId: String): String? = db.readableDatabase.rawQuery(
        "SELECT name FROM tracks WHERE id=? LIMIT 1", arrayOf(fileId)
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun tracks(): List<OdifySong> {
        val result = ArrayList<OdifySong>()
        val hidden = prefs.getStringSet("hidden", emptySet()) ?: emptySet()
        db.readableDatabase.rawQuery(
            "SELECT id,name,title,artist,album,genre,cover,lyrics,meta_source FROM tracks ORDER BY modified DESC,name LIMIT 100000",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val fileId = cursor.getString(0)
                if (fileId in hidden) continue
                val recordedTitle = cursor.getString(2)
                val recordedArtist = cursor.getString(3)
                val userEdited = cursor.getString(8) == "user"
                val manualCover = prefs.getString("manualArt:$fileId", null)
                    ?.takeIf { File(it).isFile }
                result += OdifySong(
                    id = "gdrive:$fileId",
                    title = if (userEdited) recordedTitle
                        else OdifyTitlePolicy.title(recordedTitle, recordedArtist),
                    artist = if (userEdited) recordedArtist
                        else OdifyTitlePolicy.artist(recordedArtist),
                    album = cursor.getString(4),
                    filePath = "", artworkPath = manualCover ?:
                        cursor.getString(6).takeIf { it.isNotBlank() },
                    sourceUri = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media",
                    genre = cursor.getString(5),
                    lyrics = cursor.getString(7).orEmpty().take(20000)
                )
            }
        }
        return result
    }

    /**
     * Browse the selected music root and Odify's named upload roots recursively.
     * Sync is transactional: unsuccessful network listing leaves old catalog.
     * Volume-limited to 50k files and 4k folders to prevent runaway scans.
     */
    suspend fun sync(onProgress: (Int) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        val queue = ArrayDeque<Pair<String,String>>()
        // Include the user's explicitly selected root plus both Drive
        // Auto_Upload roots, even when one is outside Odify_Music_Test.
        // Resolve by name on every device: IDs remain private and never
        // hard-coded into an APK, and new remote uploads are picked up.
        val roots = linkedSetOf<String>()
        if (folderId.isNotBlank()) roots += folderId
        val names = ODIFY_LIBRARY_ROOT_NAMES.joinToString(" or ") {
            "name = '${it.replace("'", "\\'")}'"
        }
        val filter = "mimeType = '$FOLDER_MIME' and trashed = false and ($names)"
        var rootPage: String? = null
        val rootPages = HashSet<String>()
        do {
            check(rootPages.add(rootPage.orEmpty())) { "Drive 폴더 페이지가 반복되었습니다." }
            val result = JSONObject(apiJson("files", mapOf(
                "q" to filter, "pageSize" to "1000",
                "fields" to "nextPageToken,files(id,name)",
                "pageToken" to (rootPage ?: "")
            )))
            val entries = result.getJSONArray("files")
            for (i in 0 until entries.length()) {
                roots += entries.getJSONObject(i).getString("id")
            }
            check(roots.size <= 4_000) { "폴더 수 제한(4,000)을 초과했습니다." }
            rootPage = result.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (rootPage != null)
        if (roots.isEmpty()) error("Drive에서 Odify_Music_Test 또는 Odify_Auto_Upload 폴더를 찾지 못했습니다.")
        roots.forEach { queue.add(it to "") }
        val scannedFolders = mutableSetOf<String>()
        val collected = LinkedHashMap<String, JSONObject>()
        var visitedFolders = 0
        while (queue.isNotEmpty()) {
            val (parent, genrePath) = queue.removeFirst()
            if (!scannedFolders.add(parent)) continue
            if (++visitedFolders > 4_000) error("폴더 수 제한(4,000)을 초과했습니다.")
            var nextPage: String? = null
            do {
                val query = "'$parent' in parents and trashed = false"
                val params = linkedMapOf(
                    "q" to query,
                    "pageSize" to "1000",
                    "fields" to "nextPageToken,files(id,name,mimeType,modifiedTime,md5Checksum,size,capabilities/canDownload)",
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
                val hasTags = existing?.get(5) in setOf("tag", "user", "master")
                val cv = ContentValues().apply {
                    put("id", id)
                    put("name", file.getString("name"))
                    put("title", if (hasTags) existing!![0] else fallbackTitle)
                    put("artist", if (hasTags) existing!![1] else fallbackArtist)
                    put("album", if (hasTags) existing!![2] else "")
                    put("genre", if (hasTags && !existing!![8].isNullOrBlank()) existing[8] else genre)
                    put("mime", file.optString("mimeType"))
                    put("modified", modified)
                    put("md5", file.optString("md5Checksum"))
                    put("file_bytes", file.optLong("size", 0L))
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
                val retryAfter = prefs.getLong("metaRetry:$id", 0L)
                if (retryAfter > System.currentTimeMillis()) continue
                candidates += OdifySong(
                    id = "gdrive:$id", title = cursor.getString(2),
                    artist = cursor.getString(3), album = cursor.getString(4),
                    filePath = "",
                    sourceUri = "https://www.googleapis.com/drive/v3/files/$id?alt=media"
                )
            }
        }
        for (song in candidates) {
            val parsed = ensureMetadata(song)
            val id = song.id.removePrefix("gdrive:")
            val marked = db.readableDatabase.rawQuery(
                "SELECT modified,meta_checked_modified FROM tracks WHERE id=?",
                arrayOf(id)
            ).use { c ->
                c.moveToFirst() && c.getString(0) == c.getString(1)
            }
            if (!marked) {
                // Network/auth failure: defer this one file rather than
                // retrying it in every batch and starving other tracks.
                prefs.edit().putLong("metaRetry:$id",
                    System.currentTimeMillis() + 10L * 60L * 1000L).apply()
            }
        }
        candidates.size
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
            if (origin != "user" && origin != "master") {
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
            "$driveId-${java.util.Arrays.hashCode(bytes)}.jpg")
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
