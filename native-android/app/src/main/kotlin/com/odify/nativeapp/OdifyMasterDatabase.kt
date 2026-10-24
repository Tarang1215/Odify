package com.odify.nativeapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/** Existing Google Sheets MASTER tab is the source of truth.
 * A local CSV snapshot is retained for fast offline browsing and export.
 * Google Drive OAuth remains READ ONLY.
 */
data class MasterTrack(
    val trackId: String, val year: String, val genre: String,
    val artist: String, val title: String, val album: String,
    val youtubeUrl: String, val verified: Boolean,
    val downloadStatus: String, val driveId: String, val version: String
)
enum class MasterPresence { ON_DRIVE, POSSIBLE_DUPLICATE, NOT_ON_DRIVE }
data class MasterDisplayRow(val track: MasterTrack, val presence: MasterPresence)

object MasterDuplicateDetector {
    fun norm(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "")
    fun isCover(value: String): Boolean =
        listOf("cover","커버","live","라이브","acoustic","acappella",
            "remix","리믹스","inst","instrumental").any {
            value.contains(it, ignoreCase = true)
        }

    fun status(track: MasterTrack, drive: List<OdifySong>): MasterPresence {
        if (track.driveId.isNotBlank() && drive.any {
                it.id == "gdrive:${track.driveId}"
            }) return MasterPresence.ON_DRIVE
        val title = norm(track.title)
        val artist = norm(track.artist)
        val candidates = drive.filter { norm(it.title) == title }
        if (candidates.isEmpty()) return MasterPresence.NOT_ON_DRIVE
        val sameArtist = candidates.any { norm(it.artist) == artist }
        // Matching song titles by a different artist may be an intentional
        // cover. Never suppress uploads based on title only.
        if (!sameArtist) return MasterPresence.NOT_ON_DRIVE
        if (isCover(track.version)) return MasterPresence.POSSIBLE_DUPLICATE
        return MasterPresence.POSSIBLE_DUPLICATE
    }
}

class OdifyMasterDatabase(private val context: Context) {
    private val cache = File(context.filesDir, "odify_master_snapshot.csv")
    private val prefs = context.getSharedPreferences("odify_master_sync", 0)
    private val sheetMime = "application/vnd.google-apps.spreadsheet"

    val lastUpdated: Long get() = prefs.getLong("lastUpdated", 0L)

    suspend fun loadCached(): List<MasterTrack> = withContext(Dispatchers.IO) {
        if (!cache.isFile) emptyList()
        else runCatching { parseCsv(cache.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
    }

    suspend fun exportSnapshot(): ByteArray? = withContext(Dispatchers.IO) {
        cache.takeIf { it.isFile }?.readBytes()
    }

    /**
     * Find the user's Odify music master by sheet name rather than embedding
     * a private document ID in the publicly visible application source.
     */
    suspend fun sync(): List<MasterTrack> = withContext(Dispatchers.IO) {
        val q = "mimeType = '$sheetMime' and name contains 'Odify' and trashed = false"
        val listing = get("https://www.googleapis.com/drive/v3/files?q=${enc(q)}&fields=" +
            enc("nextPageToken,files(id,name,mimeType)") + "&pageSize=100")
        val result = JSONObject(listing).getJSONArray("files")
        var sheetId: String? = null
        for (i in 0 until result.length()) {
            val f = result.getJSONObject(i)
            val name = f.optString("name")
            if (name.contains("음악 마스터")) {
                sheetId = f.getString("id")
                break
            }
        }
        require(sheetId != null) { "Odify 음악 마스터 Google Sheets를 찾지 못했습니다." }
        // CSV export targets the first worksheet. The discovered user's
        // MASTER tab is first, so validate headers to avoid wrong-sheet use.
        val csv = get("https://www.googleapis.com/drive/v3/files/$sheetId/export?" +
            "mimeType=" + enc("text/csv"), maxBytes = 9_000_000)
        val rows = parseCsv(csv)
        require(rows.isNotEmpty()) { "MASTER 탭이 비었거나 CSV 형식이 아닙니다." }
        val temp = File(context.filesDir, "odify_master_snapshot.partial")
        temp.writeText(csv, Charsets.UTF_8)
        check(temp.renameTo(cache) || runCatching {
            temp.copyTo(cache, overwrite = true)
            temp.delete()
        }.isSuccess)
        prefs.edit().putLong("lastUpdated", System.currentTimeMillis()).apply()
        rows
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun get(url: String, maxBytes: Int = 10_000_000): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer ${DriveAuth.tokenBlocking(context)}")
            conn.setRequestProperty("Accept", "text/csv,application/json")
            val status = conn.responseCode
            check(status == 200) { "Google Drive MASTER 조회 실패 HTTP $status" }
            conn.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    check(output.size() + read <= maxBytes) { "MASTER 내보내기는 9MB 이하여야 합니다." }
                    output.write(buf, 0, read)
                }
                return output.toString("UTF-8")
            }
        } finally { conn.disconnect() }
    }

    fun parseCsv(csv: String): List<MasterTrack> {
        val rows = mutableListOf<List<String>>()
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        val text = csv.removePrefix("\uFEFF")
        fun cell() { cells.add(current.toString()); current.setLength(0) }
        fun row() { cell(); rows.add(cells.toList()); cells.clear() }
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '"' && quoted && i+1 < text.length && text[i+1] == '"' -> {
                    current.append('"'); i++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> cell()
                (ch == '\n' || ch == '\r') && !quoted -> {
                    row()
                    if (ch == '\r' && i+1 < text.length && text[i+1]=='\n') i++
                }
                else -> current.append(ch)
            }
            i++
        }
        if (current.isNotEmpty() || cells.isNotEmpty()) row()
        if (rows.isEmpty()) return emptyList()
        val headers = rows.first()
        fun ix(key: String): Int = headers.indexOf(key)
        require(ix("Track ID") >= 0 && ix("곡명") >= 0 && ix("아티스트") >= 0) {
            "MASTER 탭이 첫 번째 시트인지 확인하세요."
        }
        fun at(r: List<String>, key: String): String =
            ix(key).takeIf { it >= 0 }?.let { r.getOrNull(it) }.orEmpty().trim()
        return rows.drop(1).filter { at(it, "Track ID").startsWith("ODF") }
            .map { r ->
                val url = at(r, "Drive 파일 URL")
                MasterTrack(
                    trackId = at(r, "Track ID"), year = at(r, "연도"),
                    genre = at(r, "장르"), artist = at(r, "아티스트"),
                    title = at(r, "곡명"), album = at(r, "앨범"),
                    youtubeUrl = at(r, "YouTube 오디오 URL"),
                    verified = at(r, "링크검증") == "검증완료",
                    downloadStatus = at(r, "다운로드"),
                    driveId = Regex("/d/([A-Za-z0-9_-]+)").find(url)
                        ?.groupValues?.getOrNull(1).orEmpty(),
                    version = at(r, "음원 버전")
                )
            }
    }
}
