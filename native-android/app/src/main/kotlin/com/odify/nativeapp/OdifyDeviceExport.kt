package com.odify.nativeapp

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
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
            ?: when (song.sourceUri?.let { context.contentResolver.getType(Uri.parse(it)) }) {
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
        val source = song.sourceUri?.let(Uri::parse)
        val stream: InputStream
        var connection: HttpURLConnection? = null
        if (song.id.startsWith("gdrive:")) {
            val fileId = song.id.removePrefix("gdrive:")
            require(fileId.matches(Regex("[A-Za-z0-9_-]{10,200}"))) {
                "Google Drive 음원 식별자가 잘못되었습니다."
            }
            connection = URL(
                "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
            ).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization",
                "Bearer ${DriveAuth.tokenBlocking(context)}")
            check(connection.responseCode == 200) {
                "Drive 음악 다운로드 실패 (HTTP ${connection.responseCode})"
            }
            stream = connection.inputStream
        } else if (source?.scheme == "content") {
            stream = context.contentResolver.openInputStream(source)
                ?: error("음악 파일을 열지 못했습니다.")
        } else {
            val file = File(song.filePath)
            require(file.isFile && file.canRead()) { "로컬 음원 파일을 찾을 수 없습니다." }
            stream = FileInputStream(file)
        }
        try {
            var written = 0L
            stream.use { input ->
                val output = context.contentResolver.openOutputStream(destination, "w")
                    ?: error("선택한 저장 위치를 열 수 없습니다.")
                output.use { out ->
                    val chunk = ByteArray(128 * 1024)
                    while (true) {
                        val amount = input.read(chunk)
                        if (amount == -1) break
                        written += amount
                        check(written <= maxBytes) { "256MB를 초과하는 파일은 저장할 수 없습니다." }
                        out.write(chunk, 0, amount)
                    }
                    out.flush()
                }
            }
            require(written > 0) { "원본 음원에 데이터가 없습니다." }
            written
        } catch (failure: Exception) {
            // The document was just created by ACTION_CREATE_DOCUMENT.
            // Don't leave a half-written file that appears to be a playable track.
            runCatching { context.contentResolver.delete(destination, null, null) }
            throw failure
        } finally {
            connection?.disconnect()
        }
    }
}
