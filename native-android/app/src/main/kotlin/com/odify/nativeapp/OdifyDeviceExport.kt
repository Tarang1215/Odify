package com.odify.nativeapp

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Writes the user's selected song to a document chosen by Android DocumentsUI.
 * Unlike Media3's LRU streaming cache, this is a full, persistent export.
 * Never requests broad storage permissions or deletes original source audio.
 */
class OdifyDeviceExport(private val context: Context) {
    private val maxBytes = 256L * 1024L * 1024L

    fun suggestedName(song: OdifySong, cloudName: String? = null): String {
        val supported = setOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "aac")
        val sourcePath = song.sourceUri?.let { Uri.parse(it).lastPathSegment }.orEmpty()
        val candidates = listOfNotNull(cloudName, song.filePath, sourcePath)
        val ext = candidates.map { it.substringAfterLast('.', "").lowercase() }
            .firstOrNull { it in supported }
            ?: when (song.sourceUri?.let { runCatching {
                context.contentResolver.getType(Uri.parse(it)) }.getOrNull() }) {
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/mp4", "audio/x-m4a" -> "m4a"
                "audio/flac", "audio/x-flac" -> "flac"
                "audio/wav", "audio/x-wav" -> "wav"
                "audio/ogg" -> "ogg"
                else -> "m4a"
            }
        val friendly = "${song.artist} - ${song.title}".trim().trimStart('-').trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(140)
            .ifBlank { "Odify music" }
        return "$friendly.$ext"
    }

    suspend fun save(song: OdifySong, destination: Uri): Long = withContext(Dispatchers.IO) {
        require(destination.scheme == "content") { "저장할 폴더/파일을 선택하세요." }
        require(destination.toString() != song.sourceUri) { "원본 음원과 다른 저장 위치를 선택하세요." }
        val source = song.sourceUri?.let(Uri::parse)
        val stream: InputStream
        var connection: HttpURLConnection? = null
        try {
        if (song.id.startsWith("gdrive:")) {
            val fileId = song.id.removePrefix("gdrive:")
            connection = driveConnection(fileId)
            stream = driveStream(connection)
        } else if (source?.scheme == "content") {
            stream = context.contentResolver.openInputStream(source)
                ?: error("음악 파일을 열지 못했습니다.")
        } else {
            val file = File(song.filePath)
            require(file.isFile && file.canRead()) { "로컬 음원 파일을 찾을 수 없습니다." }
            stream = FileInputStream(file)
        }
            stream.use { input ->
                val output = context.contentResolver.openOutputStream(destination, "w")
                    ?: error("선택한 저장 위치를 열 수 없습니다.")
                output.use { copy(input,it) }
            }
        } catch (failure: Exception) {
            // The document was just created by ACTION_CREATE_DOCUMENT.
            // Don't leave a half-written file that appears to be a playable track.
            runCatching { context.contentResolver.delete(destination, null, null) }
            throw failure
        } finally {
            connection?.disconnect()
        }
    }

    internal suspend fun copyDriveTo(fileId:String, destination:File) = withContext(Dispatchers.IO) {
        val connection=driveConnection(fileId)
        try {
            driveStream(connection).use {input->destination.outputStream().use {copy(input,it)}}
        } finally {connection.disconnect()}
    }

    private fun driveConnection(fileId:String):HttpURLConnection {
        require(fileId.matches(Regex("[A-Za-z0-9_-]{10,200}"))) {"Google Drive 음원 식별자가 잘못되었습니다."}
        val token=DriveAuth.tokenBlocking(context)
        return (URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media").openConnection() as HttpURLConnection).apply {
            connectTimeout=15_000
            readTimeout=45_000
            instanceFollowRedirects=false
            setRequestProperty("Authorization","Bearer $token")
        }
    }

    private fun driveStream(connection:HttpURLConnection):InputStream {
        check(connection.responseCode==200) {"Drive 음악 다운로드 실패 (HTTP ${connection.responseCode})"}
        return connection.inputStream
    }

    private suspend fun copy(input:InputStream,output:OutputStream):Long {
        var written=0L
        val chunk=ByteArray(128*1024)
        while(true) {
            currentCoroutineContext().ensureActive()
            val amount=input.read(chunk)
            if(amount<0)break
            written+=amount
            check(written<=maxBytes) {"256MB를 초과하는 파일은 저장할 수 없습니다."}
            output.write(chunk,0,amount)
        }
        output.flush()
        require(written>0) {"원본 음원에 데이터가 없습니다."}
        return written
    }
}
