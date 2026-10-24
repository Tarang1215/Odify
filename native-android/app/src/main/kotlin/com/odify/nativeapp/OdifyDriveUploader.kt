package com.odify.nativeapp

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

sealed class OdifyUploadResult {
    data class Added(val name: String, val driveId: String): OdifyUploadResult()
    data class Skipped(val name: String, val reason: String): OdifyUploadResult()
}

/**
 * Uploads user-selected, locally accessible music only.
 * Uses Drive's limited drive.file scope; never bulk-scrapes or downloads music.
 * Distinct from read-only MASTER/Drive catalog: original files stay untouched.
 */
class OdifyDriveUploader(private val context: Context) {
    private val prefs = context.getSharedPreferences("odify_cloud_upload_v1", 0)
    private val folderName = "Odify_Cloud_Uploads"
    private val maxBytes = 128L * 1024L * 1024L
    private val formats = setOf("mp3", "m4a", "flac", "wav", "ogg", "aac", "opus")

    suspend fun upload(
        selected: Uri, catalog: DriveCatalog,
        notify: (String)->Unit = {}
    ): OdifyUploadResult = withContext(Dispatchers.IO) {
        val displayName = context.contentResolver.query(selected,
            arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: selected.lastPathSegment ?: "music.mp3"
        val name = displayName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f]"), "").take(180)
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(ext in formats) { "지원하지 않는 음악 형식: $name" }
        val mime = when(ext) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "aac" -> "audio/aac"
            else -> "audio/opus"
        }
        val temp = File.createTempFile("odify_upload_", ".$ext", context.cacheDir)
        try {
            val md5 = MessageDigest.getInstance("MD5")
            var total = 0L
            notify("$name · 중복 검사 준비")
            context.contentResolver.openInputStream(selected)?.use { input ->
                temp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maxBytes) { "128MB 이하 파일만 업로드할 수 있습니다." }
                        md5.update(buffer, 0, count)
                        out.write(buffer, 0, count)
                    }
                }
            } ?: error("선택한 파일을 읽을 수 없습니다.")
            require(total > 0L) { "비어 있는 파일은 저장할 수 없습니다." }
            val digest = md5.digest().joinToString("") { "%02x".format(it) }

            if (catalog.hasExactBytes(digest,total))
                return@withContext OdifyUploadResult.Skipped(name, "Drive에 동일 바이트 파일 존재")
            val trackCode = Regex("ODF[0-9]{6}", RegexOption.IGNORE_CASE)
                .find(name)?.value
            if (trackCode != null && catalog.hasTrackCode(trackCode))
                return@withContext OdifyUploadResult.Skipped(name, "동일 Track ID가 이미 있음")

            // Audio metadata identity is a *secondary* guard; covers/remixes
            // with different artist or album are not mistaken for duplicates.
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(temp.absolutePath)
                    val title = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
                    val artist = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
                    val album = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
                    Triple(title,artist,album)
                } finally { retriever.release() }
            }.getOrNull()?.let { (title,artist,album) ->
                if (catalog.hasMatchingSong(title,artist,album))
                    return@withContext OdifyUploadResult.Skipped(
                        name, "같은 곡명·가수·앨범이 이미 Drive에 있음"
                    )
            }

            // Only now request a writable destination: avoid creating empty
            // folders when all user-selected tracks were duplicates.
            val token = DriveAuth.uploadTokenBlocking(context)
            val folderId = ensureFolder(token)
            notify("$name · Drive에 동일 파일 있는지 확인")
            if (folderHasMd5(token,folderId,digest,total))
                return@withContext OdifyUploadResult.Skipped(name, "업로드 폴더에 동일 파일 존재")
            notify("$name · 업로드 중")
            val uploadedId = uploadResumable(
                token,folderId,name,mime,temp,total
            )
            OdifyUploadResult.Added(name,uploadedId)
        } finally {
            temp.delete()
        }
    }

    private fun ensureFolder(token: String): String {
        val existing = prefs.getString("folderId","").orEmpty()
        if (existing.isNotBlank() && runCatching {
            getJson("https://www.googleapis.com/drive/v3/files/$existing?fields=id",token)
        }.isSuccess) return existing
        val q = "name = '$folderName' and mimeType = " +
            "'application/vnd.google-apps.folder' and trashed = false"
        val list = JSONObject(getJson("https://www.googleapis.com/drive/v3/files" +
            "?q=" + enc(q) + "&fields=" + enc("nextPageToken,files(id,name)") +
            "&pageSize=100",token))
        val results = list.getJSONArray("files")
        val id = if(results.length() > 0) results.getJSONObject(0).getString("id")
            else createFolder(token)
        prefs.edit().putString("folderId",id).apply()
        return id
    }

    private fun createFolder(token: String): String {
        val url = URL("https://www.googleapis.com/drive/v3/files?fields=id,name")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000; conn.readTimeout = 25_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            val data = JSONObject().put("name",folderName).put("mimeType",
                "application/vnd.google-apps.folder").toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(data.size)
            conn.outputStream.use { it.write(data) }
            check(conn.responseCode == 200) { "Drive 폴더 생성 실패 HTTP ${conn.responseCode}" }
            return JSONObject(conn.inputStream.bufferedReader().use {
                it.readText() }).getString("id")
        } finally { conn.disconnect() }
    }

    private fun folderHasMd5(token: String, folder: String, md5: String, size: Long): Boolean {
        var page: String? = null
        do {
            val q = "'$folder' in parents and trashed = false"
            val uri = "https://www.googleapis.com/drive/v3/files?q=" + enc(q) +
                "&fields=" + enc("nextPageToken,files(id,name,md5Checksum,size)") +
                "&pageSize=1000" + (page?.let { "&pageToken=" + enc(it) } ?: "")
            val result = JSONObject(getJson(uri,token))
            val files = result.getJSONArray("files")
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                if (f.optString("md5Checksum").equals(md5,true) &&
                    f.optLong("size",-1L) == size) return true
            }
            page = result.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while(page != null)
        return false
    }

    private fun uploadResumable(
        token: String, folder: String, name: String, mime: String,
        source: File, size: Long
    ): String {
        val meta = JSONObject()
            .put("name",name)
            .put("mimeType",mime)
            .put("parents", JSONArray().put(folder)).toString().toByteArray(Charsets.UTF_8)
        val init = URL("https://www.googleapis.com/upload/drive/v3/files?" +
            "uploadType=resumable&fields=id,name,md5Checksum,size")
            .openConnection() as HttpURLConnection
        val session: String
        try {
            init.requestMethod = "POST"
            init.connectTimeout = 15_000; init.readTimeout = 30_000
            init.doOutput = true
            init.instanceFollowRedirects = false
            init.setRequestProperty("Authorization", "Bearer $token")
            init.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            init.setRequestProperty("X-Upload-Content-Type",mime)
            init.setRequestProperty("X-Upload-Content-Length",size.toString())
            init.setFixedLengthStreamingMode(meta.size)
            init.outputStream.use { it.write(meta) }
            check(init.responseCode == 200) {
                "Drive 업로드 시작 실패 HTTP ${init.responseCode}"
            }
            session = init.getHeaderField("Location") ?: error("업로드 주소 없음")
        } finally { init.disconnect() }
        val location = java.net.URI(session)
        require(location.scheme == "https" &&
            location.host in setOf("www.googleapis.com","content.googleapis.com")) {
            "Google Drive 외부 업로드 주소가 반환되었습니다."
        }
        val dataConn = location.toURL().openConnection() as HttpURLConnection
        try {
            dataConn.requestMethod = "PUT"
            dataConn.doOutput = true
            dataConn.instanceFollowRedirects = false
            dataConn.connectTimeout = 20_000; dataConn.readTimeout = 90_000
            dataConn.setRequestProperty("Content-Type",mime)
            dataConn.setFixedLengthStreamingMode(size)
            FileInputStream(source).use { input ->
                dataConn.outputStream.use { out -> input.copyTo(out, 128*1024) }
            }
            check(dataConn.responseCode in 200..201) {
                "Drive 업로드 실패 HTTP ${dataConn.responseCode}"
            }
            return JSONObject(dataConn.inputStream.bufferedReader().use {
                it.readText() }).getString("id")
        } finally { dataConn.disconnect() }
    }

    private fun getJson(uri: String,token: String): String {
        val conn = URL(uri).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000; conn.readTimeout = 25_000
            conn.setRequestProperty("Authorization","Bearer $token")
            check(conn.responseCode == 200) { "Drive 조회 실패 HTTP ${conn.responseCode}" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }
    private fun enc(value: String) = URLEncoder.encode(value,"UTF-8")
}
