package com.odify.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Extract embedded MP3 APIC artwork using only the first 2 MiB HTTP Range.
 * Never download an entire song just to show its cover. Works for ID3v2.3/2.4;
 * unsupported containers deliberately show the neutral placeholder.
 */
object OdifyDriveArtwork {
    private fun int32(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 255) shl 24) or
        ((b[i + 1].toInt() and 255) shl 16) or
        ((b[i + 2].toInt() and 255) shl 8) or (b[i + 3].toInt() and 255)

    private fun synchsafe(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 127) shl 21) or
        ((b[i+1].toInt() and 127) shl 14) or
        ((b[i+2].toInt() and 127) shl 7) or
        (b[i+3].toInt() and 127)

    private fun picture(bytes: ByteArray): ByteArray? {
        if (bytes.size < 40 || String(bytes,0,3) != "ID3") return null
        val version = bytes[3].toInt()
        if (version != 3 && version != 4) return null
        val tagEnd = (synchsafe(bytes, 6) + 10).coerceAtMost(bytes.size)
        var offset = 10
        while (offset + 10 < tagEnd) {
            val name = String(bytes, offset, 4, Charsets.ISO_8859_1)
            val size = if (version == 4) synchsafe(bytes, offset + 4)
                else int32(bytes, offset + 4)
            if (size <= 0 || offset + 10L + size > tagEnd) break
            if (name == "APIC") {
                val start = offset + 10
                val end = start + size
                val encoding = bytes[start].toInt() and 255
                var p = start + 1
                while (p < end && bytes[p].toInt() != 0) p++
                p += 2 // MIME terminator and picture type
                if (encoding == 1 || encoding == 2) {
                    while (p + 1 < end &&
                        !(bytes[p].toInt()==0 && bytes[p+1].toInt()==0)) p += 2
                    p += 2
                } else {
                    while (p < end && bytes[p].toInt()!=0) p++
                    p++
                }
                if (p < end) return bytes.copyOfRange(p,end)
            }
            offset += 10 + size
        }
        return null
    }

    suspend fun warm(context: Context, song: OdifySong): String? =
        withContext(Dispatchers.IO) {
            if (!song.id.startsWith("gdrive-")) return@withContext null
            if (!song.artworkPath.isNullOrBlank() &&
                File(song.artworkPath).isFile) return@withContext song.artworkPath
            val cover = File(context.filesDir, "odify_covers/${song.id}.jpg")
            if (cover.isFile) return@withContext cover.absolutePath
            runCatching {
                val id = song.id.removePrefix("gdrive-")
                val header = OdifyDriveApi.firstBytes(context,id,2_000_000)
                val raw = picture(header) ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                if (bounds.outWidth !in 1..12000 || bounds.outHeight !in 1..12000)
                    return@runCatching null
                var scale = 1
                while (bounds.outWidth / scale > 720 || bounds.outHeight / scale > 720)
                    scale *= 2
                val bitmap = BitmapFactory.decodeByteArray(raw,0,raw.size,
                    BitmapFactory.Options().apply { inSampleSize = scale })
                    ?: return@runCatching null
                cover.parentFile?.mkdirs()
                FileOutputStream(cover).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                bitmap.recycle()
                OdifyDriveCatalogDb(context).cover(song.id, cover.absolutePath)
                cover.absolutePath
            }.getOrNull()
        }
}