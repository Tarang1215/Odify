package com.odify.nativeapp

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale

/**
 * The user selects exactly one device folder through SAF. Tracks are read via
 * persisted content:// URIs, NOT uploaded, copied, or recursively scanned
 * outside the explicitly selected folder tree.
 */
class OdifyPhoneFolder(private val context: Context) {
    private val prefs = context.getSharedPreferences("odify_phone_folder_v1",0)
    private val covers = File(context.filesDir, "phone_folder_covers").apply { mkdirs() }
    private val extensions = setOf("mp3","flac","m4a","aac","wav","ogg","opus")
    val folderLabel: String get() = prefs.getString("folderLabel","").orEmpty()
    val hasFolder: Boolean get() = prefs.getString("folderUri",null) != null

    fun select(uri: Uri) {
        require(uri.scheme == "content") { "휴대폰 폴더를 선택해 주세요." }
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        require(DocumentsContract.isTreeUri(uri)) { "폴더 접근 권한이 필요합니다." }
        prefs.edit().putString("folderUri",uri.toString())
            .putString("folderLabel",uri.lastPathSegment.orEmpty().substringAfterLast(':'))
            .apply()
    }

    /**
     * Only the app catalog is edited/hidden. SAF grants remain read-only and
     * removing a linked track must never delete the user's original file.
     */
    fun updateMetadata(song: OdifySong, title: String, artist: String, album: String) {
        require(song.isPhoneFolder)
        require(title.trim().isNotEmpty()) { "곡명을 입력해 주세요." }
        val overrides = JSONObject(prefs.getString("overrides", "{}").orEmpty())
        overrides.put(song.id, JSONObject().apply {
            put("title", title.trim()); put("artist", artist.trim())
            put("album", album.trim())
        })
        prefs.edit().putString("overrides", overrides.toString()).apply()
    }

    fun hideTrack(song: OdifySong) {
        require(song.isPhoneFolder)
        val hidden = prefs.getStringSet("hidden", emptySet())?.toMutableSet()
            ?: mutableSetOf()
        hidden.add(song.id)
        prefs.edit().putStringSet("hidden", hidden).apply()
    }

    fun tracks(): List<OdifySong> {
        if (!hasFolder) return emptyList()
        val raw = prefs.getString("index","[]").orEmpty()
        val hidden = prefs.getStringSet("hidden", emptySet()).orEmpty()
        val overrides = runCatching {
            JSONObject(prefs.getString("overrides", "{}").orEmpty())
        }.getOrDefault(JSONObject())
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val j = arr.optJSONObject(i) ?: return@mapNotNull null
                val uri = j.optString("sourceUri")
                val id = j.optString("id")
                if (!uri.startsWith("content://") || id in hidden)
                    return@mapNotNull null
                val edited = overrides.optJSONObject(id)
                OdifySong(
                    id=id,title=edited?.optString("title") ?: j.optString("title"),
                    artist=edited?.optString("artist") ?: j.optString("artist"),
                    album=edited?.optString("album") ?: j.optString("album"),
                    filePath="",sourceUri=uri,
                    artworkPath=j.optString("artworkPath").takeIf { it.isNotBlank() },
                    durationMs=j.optLong("durationMs"),genre=j.optString("genre")
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun scan(onProgress:(Int)->Unit={}): Int = withContext(Dispatchers.IO) {
        val tree = prefs.getString("folderUri",null)?.let(Uri::parse)
            ?: error("먼저 휴대폰 음악 폴더를 선택해 주세요.")
        check(context.contentResolver.persistedUriPermissions.any {
            it.isReadPermission && it.uri == tree
        }) { "선택한 폴더 접근 권한이 만료됐습니다. 다시 선택해 주세요." }
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val folders = ArrayDeque<String>()
        folders.add(rootId)
        val collected = ArrayList<OdifySong>()
        val previous = tracks().associateBy(OdifySong::id)
        var visited=0
        while(folders.isNotEmpty()) {
            if(++visited>500) error("하위 폴더 500개를 초과했습니다.")
            val folder=folders.removeFirst()
            val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,folder)
            val columns=arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            context.contentResolver.query(children,columns,null,null,null)?.use { cursor ->
                val idIndex=cursor.getColumnIndexOrThrow(columns[0])
                val nameIndex=cursor.getColumnIndexOrThrow(columns[1])
                val mimeIndex=cursor.getColumnIndexOrThrow(columns[2])
                while(cursor.moveToNext()) {
                    val id=cursor.getString(idIndex)
                    val name=cursor.getString(nameIndex) ?: continue
                    val mime=cursor.getString(mimeIndex).orEmpty()
                    if(mime==DocumentsContract.Document.MIME_TYPE_DIR) {
                        folders.add(id);continue
                    }
                    if(name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in extensions) continue
                    if(collected.size>=5000) error("로컬 음악 폴더는 최대 5,000곡입니다.")
                    val songUri=DocumentsContract.buildDocumentUriUsingTree(tree,id)
                    val key = "localfolder:" + MessageDigest.getInstance("SHA-256")
                        .digest(songUri.toString().toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    val old=previous[key]
                    val track = old ?: readMetadata(key,name,songUri)
                    collected += track
                    if(collected.size%25==0) onProgress(collected.size)
                }
            } ?: error("휴대폰 폴더를 열 수 없습니다.")
        }
        // Atomically replace cached index. Never delete any original audio.
        val array=JSONArray()
        for(song in collected) array.put(JSONObject().apply {
            put("id",song.id);put("title",song.title);put("artist",song.artist)
            put("album",song.album);put("sourceUri",song.sourceUri)
            put("artworkPath",song.artworkPath ?: "")
            put("durationMs",song.durationMs);put("genre",song.genre)
        })
        prefs.edit().putString("index",array.toString()).apply()
        collected.size
    }

    private fun readMetadata(id:String,name:String,uri:Uri):OdifySong {
        val fallback=DriveTagReader.fallbackFromName(name)
        var title=fallback.first
        var artist=fallback.second
        var album=""
        var duration=0L
        var picture:String?=null
        val retriever=MediaMetadataRetriever()
        try {
            retriever.setDataSource(context,uri)
            title=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf(String::isNotBlank) ?: title
            artist=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf(String::isNotBlank) ?: artist
            album=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val bytes=retriever.embeddedPicture
            if(bytes!=null && bytes.size<=1_500_000) {
                val filename=id.removePrefix("localfolder:")+".jpg"
                val target=File(covers,filename)
                val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                if(bitmap!=null) try {
                    val max=maxOf(bitmap.width,bitmap.height)
                    val scaled=if(max>768) Bitmap.createScaledBitmap(
                        bitmap,(bitmap.width*768L/max).toInt().coerceAtLeast(1),
                        (bitmap.height*768L/max).toInt().coerceAtLeast(1),true
                    ) else bitmap
                    try { target.outputStream().use {
                        if(scaled.compress(Bitmap.CompressFormat.JPEG,86,it))
                            picture=target.absolutePath
                    } } finally { if(scaled!==bitmap) scaled.recycle() }
                } finally { bitmap.recycle() }
            }
        } catch (_:Exception) {
            // Keep playable SAF URI even if this provider doesn't offer tags.
        } finally { runCatching { retriever.release() } }
        return OdifySong(id=id,title=title,artist=artist,album=album,
            filePath="",sourceUri=uri.toString(),artworkPath=picture,
            durationMs=duration)
    }
}
