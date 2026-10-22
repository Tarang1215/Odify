package com.odify.nativeapp

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale

/**
 * Android Storage Access Framework folder integration.
 * Reads selected local or SD-card files in place; never uploads them to Drive
 * and never copies whole music files into Odify private storage.
 */
class OdifyLocalFolders(private val context: Context) {
    private val prefs = context.getSharedPreferences("odify_local_folders", 0)
    private val db = object : SQLiteOpenHelper(context, "odify_local_folders.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE songs(
                id TEXT PRIMARY KEY, uri TEXT NOT NULL, name TEXT NOT NULL,
                title TEXT NOT NULL, artist TEXT NOT NULL DEFAULT '',
                album TEXT NOT NULL DEFAULT '', duration INTEGER NOT NULL DEFAULT 0,
                cover TEXT NOT NULL DEFAULT '', signature TEXT NOT NULL DEFAULT ''
            )""")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, next: Int) {}
    }
    private val extensions = setOf("mp3","m4a","flac","aac","wav","ogg","opus")
    private val artDir = File(context.filesDir, "local_folder_art").apply { mkdirs() }
    val folderUri: String get() = prefs.getString("folderUri", "").orEmpty()

    fun hasPermission(): Boolean = folderUri.isNotBlank() &&
        context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == folderUri && it.isReadPermission
        }

    fun chooseFolder(uri: Uri) {
        require(uri.scheme == "content") { "Android 폴더 선택기에서 선택한 위치만 지원합니다." }
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val old = folderUri
        prefs.edit().putString("folderUri", uri.toString()).apply()
        if (old != uri.toString()) db.writableDatabase.delete("songs", null, null)
    }

    fun songs(): List<OdifySong> {
        if (!hasPermission()) return emptyList()
        val result = ArrayList<OdifySong>()
        db.readableDatabase.rawQuery(
            "SELECT id,uri,title,artist,album,duration,cover FROM songs ORDER BY title",
            null
        ).use { c ->
            while (c.moveToNext()) {
                result += OdifySong(
                    id = "saf:" + c.getString(0), title = c.getString(2),
                    artist = c.getString(3), album = c.getString(4),
                    filePath = "", sourceUri = c.getString(1),
                    durationMs = c.getLong(5),
                    artworkPath = c.getString(6).takeIf { it.isNotBlank() && File(it).isFile }
                )
            }
        }
        return result
    }

    fun removeFromLibrary(song: OdifySong) {
        if (!song.id.startsWith("saf:")) return
        db.writableDatabase.delete("songs", "id=?", arrayOf(song.id.removePrefix("saf:")))
        // Storage Access permission remains for the folder; no user file is removed.
    }

    suspend fun scan(): Int = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext 0
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
            ?: error("선택한 폴더를 열 수 없습니다.")
        val queue = ArrayDeque<DocumentFile>()
        queue.add(tree)
        val seen = HashSet<String>()
        var folders = 0
        var count = 0
        while (queue.isNotEmpty()) {
            if (++folders > 800) error("폴더가 너무 많습니다(800개 초과).")
            val folder = queue.removeFirst()
            for (entry in folder.listFiles()) {
                if (entry.isDirectory) {
                    queue.add(entry)
                    continue
                }
                val name = entry.name ?: continue
                if (!entry.isFile || name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in extensions)
                    continue
                if (++count > 20_000) error("한 폴더에서는 최대 20,000곡을 검색합니다.")
                val source = entry.uri.toString()
                val key = sha(source)
                seen.add(key)
                val signature = "${entry.length()}:${entry.lastModified()}"
                val exists = db.readableDatabase.rawQuery(
                    "SELECT signature FROM songs WHERE id=?", arrayOf(key)
                ).use { c -> c.moveToFirst() && c.getString(0) == signature }
                if (exists) continue
                val parts = DriveTagReader.fallbackFromName(name)
                var title = parts.first
                var artist = parts.second
                var album = ""
                var duration = 0L
                var cover = ""
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(context, entry.uri)
                    title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.takeIf { it.isNotBlank() } ?: title
                    artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?.takeIf { it.isNotBlank() } ?: artist
                    album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
                    duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    mmr.embeddedPicture?.takeIf { it.size < 5_000_000 }?.let {
                        saveArt(key,it)?.let { path -> cover = path }
                    }
                } catch (_: Exception) {
                    // Unsupported metadata must not prevent playback via content://.
                } finally {
                    runCatching { mmr.release() }
                }
                val row = ContentValues().apply {
                    put("id",key); put("uri",source); put("name",name)
                    put("title",OdifyTitlePolicy.title(title,artist))
                    put("artist",OdifyTitlePolicy.artist(artist))
                    put("album",album); put("duration",duration)
                    put("cover",cover); put("signature",signature)
                }
                db.writableDatabase.insertWithOnConflict(
                    "songs",null,row,SQLiteDatabase.CONFLICT_REPLACE
                )
            }
        }
        // Remove stale rows only after a complete and successful tree traversal.
        val toDelete = mutableListOf<String>()
        db.readableDatabase.rawQuery("SELECT id FROM songs",null).use { c ->
            while(c.moveToNext()) if(c.getString(0) !in seen) toDelete.add(c.getString(0))
        }
        val conn = db.writableDatabase
        conn.beginTransaction()
        try {
            toDelete.forEach { conn.delete("songs","id=?",arrayOf(it)) }
            conn.setTransactionSuccessful()
        } finally { conn.endTransaction() }
        songs().size
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun saveArt(id: String, bytes: ByteArray): String? {
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while(bounds.outWidth/sample > 768 || bounds.outHeight/sample > 768) sample *= 2
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,
            BitmapFactory.Options().apply{inSampleSize=sample}) ?: return null
        val destination=File(artDir,"$id.jpg")
        return try {
            destination.outputStream().use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG,85,it)) error("앨범커버 저장 실패")
            }
            destination.absolutePath
        } catch (_: Exception) { null } finally { bitmap.recycle() }
    }
}
