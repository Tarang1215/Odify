package com.odify.nativeapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class TimedLyric(val milliseconds: Long, val text: String)
data class LyricCandidate(
    val id: Long, val songTitle: String, val artist: String, val album: String,
    val durationSec: Int, val synchronized: String, val plain: String
) {
    val text: String get() = synchronized.ifBlank { plain }
}
data class LyricView(val text: String, val lines: List<TimedLyric>, val isTimed: Boolean)

object LrcParser {
    private val time = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    fun parse(source: String): LyricView {
        val result = ArrayList<TimedLyric>()
        val plainLines = ArrayList<String>()
        for (line in source.lines()) {
            val matches = time.findAll(line).toList()
            if (matches.isEmpty()) {
                if (line.isNotBlank() && !Regex("""^\[(ar|al|ti|by|offset):""",
                        RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                    plainLines.add(line)
                }
                continue
            }
            val text = line.replace(time, "").trim()
            for (m in matches) {
                val minutes = m.groupValues[1].toLongOrNull() ?: continue
                val secs = m.groupValues[2].toLongOrNull() ?: continue
                val fractions = m.groupValues[3]
                val millis = when (fractions.length) {
                    1 -> (fractions.toLongOrNull() ?: 0) * 100
                    2 -> (fractions.toLongOrNull() ?: 0) * 10
                    3 -> fractions.toLongOrNull() ?: 0
                    else -> 0
                }
                result.add(TimedLyric((minutes * 60 + secs) * 1000 + millis, text))
            }
        }
        val timed = result.sortedBy { it.milliseconds }
        return LyricView(source, timed,
            isTimed = timed.isNotEmpty() && timed.any { it.text.isNotBlank() })
    }
}

/**
 * Distinguish a romanized/Latin-only lyric from Korean original text.
 * Never overwrite a user-selected .lrc and never invent translated lyrics.
 */
object KoreanOriginalLyrics {
    private val hangul = Regex("[\\uAC00-\\uD7A3]")
    private fun key(value: String): String = java.text.Normalizer
        .normalize(value, java.text.Normalizer.Form.NFKC)
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    fun needsOriginal(song: OdifySong, current: String): Boolean {
        if (current.isBlank()) return false
        val likelyKorean = hangul.containsMatchIn(song.title + song.artist) ||
            listOf("k-pop", "가요", "한국", "korean").any {
                song.genre.contains(it, ignoreCase = true)
            }
        val content = LrcParser.parse(current).let {
            if (it.isTimed) it.lines.joinToString(" ") { line -> line.text }
            else current
        }
        return likelyKorean && !hangul.containsMatchIn(content) &&
            content.count(Char::isLetter) >= 50
    }

    fun verifiedOriginal(song: OdifySong, matches: List<LyricCandidate>): LyricCandidate? {
        val exact = matches.filter {
            key(it.songTitle) == key(song.title) &&
                key(it.artist) == key(song.artist) &&
                it.text.count { ch -> ch in '가'..'힣' } >= 12 &&
                (song.durationMs <= 0L || it.durationSec <= 0 ||
                    kotlin.math.abs(it.durationSec * 1000L - song.durationMs) <= 6_000L)
        }
        // If two competing arrangements match the same metadata, do not
        // silently choose a version that may have different lyrics/timing.
        return exact.singleOrNull()
    }
}

/** Lyrics are private per-song app files; an imported LRC overrides embedded tags. */
class OdifyLyricsRepository(private val context: Context) {
    private val root = File(context.filesDir, "lyrics").apply { mkdirs() }
    private val userAgent = "Odify/12 (https://github.com/Tarang1215/Odify)"
    private val retryPrefs = context.getSharedPreferences("odify_lyric_original_lookup", 0)

    suspend fun recoverKoreanOriginal(song: OdifySong, current: String): String? =
        withContext(Dispatchers.IO) {
            if (!KoreanOriginalLyrics.needsOriginal(song, current) ||
                storedFile(song).isFile) return@withContext null
            val now = System.currentTimeMillis()
            val last = retryPrefs.getLong(song.id, 0L)
            if (now - last < 24 * 60 * 60 * 1000L) return@withContext null
            // Limit external requests per song. Network failure is recoverable
            // tomorrow; manual Korean LRC import works at any time.
            retryPrefs.edit().putLong(song.id, now).apply()
            val candidate = KoreanOriginalLyrics.verifiedOriginal(
                song, search(song.title, song.artist)
            ) ?: return@withContext null
            save(song, candidate.text)
            candidate.text
        }

    private fun storedFile(song: OdifySong): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(song.id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(root, "$hash.lrc")
    }
    suspend fun read(song: OdifySong): String = withContext(Dispatchers.IO) {
        val file = storedFile(song)
        if (file.isFile) file.readText(Charsets.UTF_8)
        else song.lyrics
    }
    suspend fun save(song: OdifySong, lyrics: String) = withContext(Dispatchers.IO) {
        require(lyrics.length <= 200_000) { "가사는 200KB 미만으로 입력해 주세요." }
        storedFile(song).writeText(lyrics.trim(), Charsets.UTF_8)
    }
    suspend fun import(song: OdifySong, uri: android.net.Uri) =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use {
                it.readNBytes(200_001)
            } ?: error("가사 파일을 열 수 없습니다.")
            require(bytes.size <= 200_000) { "가사 파일이 너무 큽니다." }
            // Most modern LRCs use UTF-8, including BOM.
            save(song, String(bytes, Charsets.UTF_8).removePrefix("\uFEFF"))
        }
    suspend fun search(title: String, artist: String): List<LyricCandidate> =
        withContext(Dispatchers.IO) {
            require(title.isNotBlank()) { "곡명을 입력해 주세요." }
            val q = "track_name=${enc(title)}" +
                if (artist.isNotBlank()) "&artist_name=${enc(artist)}" else ""
            val conn = URL("https://lrclib.net/api/search?$q").openConnection()
                as HttpURLConnection
            try {
                conn.setRequestProperty("User-Agent", userAgent)
                conn.connectTimeout = 10_000; conn.readTimeout = 15_000
                if (conn.responseCode == 429) error("가사 검색 요청이 많습니다. 잠시 후 다시 시도하세요.")
                require(conn.responseCode == 200) {
                    "가사 검색 실패 HTTP ${conn.responseCode}"
                }
                val content = conn.inputStream.use { input ->
                    val bytes = input.readNBytes(1_000_001)
                    require(bytes.size <= 1_000_000) { "가사 응답이 너무 큽니다." }
                    String(bytes, Charsets.UTF_8)
                }
                val json = JSONArray(content)
                (0 until json.length()).mapNotNull { index ->
                    val x = json.optJSONObject(index) ?: return@mapNotNull null
                    val synchronized = x.optString("syncedLyrics").takeUnless { it == "null" }.orEmpty()
                    val plain = x.optString("plainLyrics").takeUnless { it == "null" }.orEmpty()
                    if (synchronized.isBlank() && plain.isBlank()) return@mapNotNull null
                    LyricCandidate(
                        id=x.optLong("id"), songTitle=x.optString("trackName"),
                        artist=x.optString("artistName"), album=x.optString("albumName"),
                        durationSec=x.optInt("duration"), synchronized=synchronized,
                        plain=plain
                    )
                }.take(20)
            } finally { conn.disconnect() }
        }
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
