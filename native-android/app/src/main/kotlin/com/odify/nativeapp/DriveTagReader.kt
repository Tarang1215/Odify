package com.odify.nativeapp

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Parse bounded byte ranges. No audio is copied into the offline music folder. */
data class DriveTagData(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val lyrics: String? = null,
    val coverBytes: ByteArray? = null
) {
    val hasAnyTag: Boolean get() =
        listOf(title, artist, album, genre, lyrics).any { !it.isNullOrBlank() } ||
        coverBytes != null
}

object DriveTagReader {
    fun parse(bytes: ByteArray, extension: String): DriveTagData = when (extension.lowercase(Locale.ROOT)) {
        "mp3" -> parseId3(bytes)
        "m4a", "mp4", "aac" -> parseMp4(bytes)
        else -> DriveTagData()
    }

    private fun readInt32(b: ByteArray, at: Int): Long {
        if (at < 0 || at + 4 > b.size) return -1L
        return ((b[at].toLong() and 255) shl 24) or
            ((b[at+1].toLong() and 255) shl 16) or
            ((b[at+2].toLong() and 255) shl 8) or (b[at+3].toLong() and 255)
    }
    private fun syncInt(b: ByteArray, at: Int): Int {
        if (at < 0 || at + 4 > b.size) return 0
        var result = 0
        for (k in at until at+4) result = (result shl 7) or (b[k].toInt() and 0x7f)
        return result
    }
    private fun decodeText(b: ByteArray, start: Int, end: Int, encoding: Int): String {
        if (start >= end || start < 0 || end > b.size) return ""
        val charset: Charset = when (encoding) {
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> StandardCharsets.ISO_8859_1
        }
        return String(b, start, end - start, charset).trim('\u0000', ' ', '\n', '\r', '\t')
            .take(20000)
    }

    /** Remove permitted ID3 padding/BOM before JPEG/PNG magic. */
    fun normalizedCoverBytes(raw: ByteArray): ByteArray? {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a)
        for (offset in 0..minOf(12, raw.size - 3)) {
            if (offset > 0 && raw.take(offset).any {
                    it != 0.toByte() && it != 0xfe.toByte() && it != 0xff.toByte()
                }) break
            if (raw.size - offset >= jpeg.size &&
                raw.copyOfRange(offset, offset+jpeg.size).contentEquals(jpeg))
                return raw.copyOfRange(offset,raw.size)
            if (raw.size - offset >= png.size &&
                raw.copyOfRange(offset,offset+png.size).contentEquals(png))
                return raw.copyOfRange(offset,raw.size)
        }
        return null
    }

    fun parseId3(b: ByteArray): DriveTagData {
        if (b.size < 10 || b[0] != 73.toByte() || b[1] != 68.toByte() || b[2] != 51.toByte())
            return DriveTagData()
        val version = b[3].toInt() and 255
        if (version !in 3..4) return DriveTagData()
        val tagSize = syncInt(b, 6).coerceAtMost(b.size - 10)
        val flags = b[5].toInt() and 255
        var at = 10
        if (flags and 0x40 != 0 && at + 4 <= b.size) {
            at += if (version == 4) syncInt(b, at) else readInt32(b, at).toInt() + 4
        }
        val end = 10 + tagSize
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var genre: String? = null
        var lyrics: String? = null
        var cover: ByteArray? = null
        while (at + 10 <= end) {
            val key = String(b, at, 4, StandardCharsets.ISO_8859_1)
            if (!key.all { it.isUpperCase() || it.isDigit() }) break
            val length = if (version == 4) syncInt(b, at + 4).toLong()
                else readInt32(b, at + 4)
            if (length <= 0L || length > 2L * 1024L * 1024L || at + 10L + length > end)
                break
            val start = at + 10
            val stop = (start + length).toInt()
            val coding = b[start].toInt() and 255
            when (key) {
                "TIT2" -> title = decodeText(b, start + 1, stop, coding)
                "TPE1" -> artist = decodeText(b, start + 1, stop, coding)
                "TALB" -> album = decodeText(b, start + 1, stop, coding)
                "TCON" -> genre = decodeText(b, start + 1, stop, coding)
                "USLT" -> {
                    // encoding(1), language(3), description terminated by 1/2 null bytes
                    var pos = start + 4
                    val unit = if (coding == 1 || coding == 2) 2 else 1
                    while (pos + unit <= stop) {
                        if (b[pos] == 0.toByte() && (unit == 1 || b[pos+1] == 0.toByte())) {
                            pos += unit; break
                        }
                        pos++
                    }
                    lyrics = decodeText(b, pos, stop, coding)
                }
                "TXXX" -> {
                    val str = decodeText(b, start+1, stop, coding)
                    val text = str.substringAfter('\u0000', "")
                    if (str.startsWith("USLT\u0000", true) && text.isNotBlank()) lyrics = text
                }
                "APIC" -> {
                    var p = start + 1
                    while (p < stop && b[p] != 0.toByte()) p++
                    p += 2 // MIME null terminator and picture type
                    val unit = if (coding == 1 || coding == 2) 2 else 1
                    while (p + unit <= stop) {
                        if (b[p] == 0.toByte() && (unit == 1 || b[p+1] == 0.toByte())) {
                            p += unit; break
                        }
                        p++
                    }
                    if (p < stop && stop-p <= 900_000) {
                        // UTF-16 picture descriptions produced by FFmpeg may
                        // include an extra 0x00 immediately before JPEG magic.
                        // BitmapFactory rejects that payload although Samsung
                        // Music recognizes the embedded image.
                        cover = normalizedCoverBytes(b.copyOfRange(p, stop))
                    }
                }
            }
            at = stop
        }
        return DriveTagData(title, artist, album, genre, lyrics, cover)
    }

    /** MP4 metadata lives in moov/udta/meta/ilst and frequently at file end. */
    fun parseMp4(b: ByteArray): DriveTagData {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var genre: String? = null
        var lyrics: String? = null
        var cover: ByteArray? = null
        // A chunk can start in the middle of a moov atom. Locate the ilst
        // atom header, then validate all nested sizes rather than decoding
        // unrelated media payload as tags.
        var i = 4
        while (i + 4 < b.size) {
            if (b[i] == 0x69.toByte() && b[i+1] == 0x6c.toByte() &&
                b[i+2] == 0x73.toByte() && b[i+3] == 0x74.toByte()) {
                val pos = i-4
                val length = readInt32(b, pos)
                if (length >= 8 && length <= 4 * 1024 * 1024) {
                    var at = pos + 8
                    val end = minOf(b.size.toLong(), pos.toLong() + length).toInt()
                    while (at + 8 <= end) {
                        val len = readInt32(b, at)
                        if (len < 8 || at + len > end) break
                        val key = String(b, at+4, 4, StandardCharsets.ISO_8859_1)
                        var inner = at+8
                        while (inner + 16 <= at + len) {
                            val childSize = readInt32(b, inner)
                            if (childSize < 16 || inner + childSize > at + len) break
                            val childKey = String(b, inner+4, 4, StandardCharsets.ISO_8859_1)
                            if (childKey == "data") {
                                val valueAt = inner+16
                                val valueEnd = (inner+childSize).toInt()
                                if (valueEnd > valueAt) {
                                    if (key == "covr" && valueEnd-valueAt <= 900_000)
                                        cover = normalizedCoverBytes(b.copyOfRange(valueAt,valueEnd))
                                    else {
                                        val value = String(b, valueAt, valueEnd-valueAt,
                                            StandardCharsets.UTF_8)
                                            .trim('\u0000',' ', '\n','\r','\t').take(20000)
                                        when (key) {
                                            "©nam" -> title = value
                                            "©ART", "aART" -> artist = value
                                            "©alb" -> album = value
                                            "©gen" -> genre = value
                                            "©lyr" -> lyrics = value
                                        }
                                    }
                                }
                            }
                            inner += childSize.toInt()
                        }
                        at += len.toInt()
                    }
                    if (title != null || artist != null || album != null || cover != null)
                        break
                }
            }
            i++
        }
        return DriveTagData(title,artist,album,genre,lyrics,cover)
    }

    /** Drive filenames are a fallback, never the first choice for tagged audio. */
    fun fallbackFromName(name: String): Pair<String,String> {
        val base = name.substringBeforeLast('.')
            .replace(Regex("^ODF[0-9]{6}\\s*[-–]\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
        val parts = base.split(" - ", limit=2)
        return if (parts.size == 2) parts[1].trim() to parts[0].trim()
            else base to ""
    }

    fun recognizableGenre(folderPath: String): String {
        val known = setOf("k-pop","j-pop","pop","rock","jazz","ballad","r&b",
            "hip-hop","hiphop","classical","electronic","edm","indie","metal",
            "country","blues","ost","anime","folk","soul","city pop","dance")
        return folderPath.split('/').map { it.trim() }
            .firstOrNull { it.lowercase(Locale.ROOT) in known }.orEmpty()
    }
}
