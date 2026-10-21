package xyz.luan.audioplayers.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.IOException

/**
 * A progressive DataSource that makes individually bounded HTTP byte-range
 * requests, but exposes a single uninterrupted stream to Media3.
 *
 * The extractor validates the same Googlevideo URL with HttpURLConnection
 * and Range: bytes=0-1023. Unbounded GETs via OkHttp can be rejected with
 * 403 even if that bounded probe succeeds. Start with an identical 1 KiB
 * request, then read 256 KiB chunks via HttpURLConnection to maintain parity.
 *
 * URL and request headers come from the ephemeral allowlisted native registry.
 * Never log either, or propagate HTTP response bodies in diagnostics.
 */
@OptIn(UnstableApi::class)
internal class OdifyBoundedRangeDataSource(
    private val httpFactory: DefaultHttpDataSource.Factory
) : DataSource {
    companion object {
        internal const val PROBE_BYTES = 1024L
        internal const val CHUNK_BYTES = 256L * 1024L
        private val CONTENT_RANGE =
            Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
        private val RANGE_NOT_SATISFIABLE =
            Regex("""bytes\s+\*/(\d+)""", RegexOption.IGNORE_CASE)
    }

    private var spec: DataSpec? = null
    private var inner: DataSource? = null
    private val transferListeners = mutableListOf<TransferListener>()
    private var offset = 0L
    private var requestedRemaining = C.LENGTH_UNSET.toLong()
    private var chunkRemaining = 0L
    private var knownTotal = C.LENGTH_UNSET.toLong()
    private var active = false
    private var completed = false
    private var responseHeaders = emptyMap<String, List<String>>()

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners.add(transferListener)
        inner?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(!active) { "Source already open" }
        require(dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET) {
            "Only GET is supported for Odify audio"
        }
        spec = dataSpec
        offset = dataSpec.position
        requestedRemaining = dataSpec.length
        knownTotal = C.LENGTH_UNSET.toLong()
        chunkRemaining = 0L
        completed = false
        active = true
        try {
            if (!openNextChunk()) {
                completed = true
                return 0L
            }
            return if (requestedRemaining != C.LENGTH_UNSET.toLong()) {
                requestedRemaining
            } else if (knownTotal != C.LENGTH_UNSET.toLong()) {
                (knownTotal - offset).coerceAtLeast(0L)
            } else C.LENGTH_UNSET.toLong()
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    private fun openNextChunk(): Boolean {
        if (completed || requestedRemaining == 0L ||
            (knownTotal != C.LENGTH_UNSET.toLong() && offset >= knownTotal)) {
            return false
        }
        val original = spec ?: throw IOException("Odify source is not open")
        val targetChunk = if (offset == 0L) PROBE_BYTES else CHUNK_BYTES
        val wanted = minOf(
            targetChunk,
            if (requestedRemaining == C.LENGTH_UNSET.toLong())
                targetChunk else requestedRemaining,
            if (knownTotal == C.LENGTH_UNSET.toLong())
                targetChunk else (knownTotal - offset),
        )
        if (wanted <= 0L) return false

        // DataSpec.subrange carries all original per-request headers/flags;
        // a finite length forces Range: bytes=<offset>-<offset+length-1>.
        val request = original.subrange(offset - original.position, wanted)
        val next = httpFactory.createDataSource()
        transferListeners.forEach(next::addTransferListener)
        try {
            next.open(request)
            val headers = next.responseHeaders
            responseHeaders = headers
            val contentRange = headers.entries.firstOrNull {
                it.key.equals("content-range", ignoreCase = true)
            }?.value?.firstOrNull()
            val match = contentRange?.let(CONTENT_RANGE::find)
            if (match != null) {
                val begin = match.groupValues[1].toLongOrNull()
                val end = match.groupValues[2].toLongOrNull()
                if (begin != offset || end == null || end < offset) {
                    throw IOException("CDN supplied an unexpected byte range")
                }
                match.groupValues[3].toLongOrNull()?.let { knownTotal = it }
                chunkRemaining = minOf(wanted, end - begin + 1L)
            } else {
                if (offset > 0L) {
                    // An origin returning a full 200 on seek would silently
                    // splice the wrong bytes into an MP4/WebM container.
                    throw IOException("CDN does not support ranged seek")
                }
                chunkRemaining = wanted
            }
            inner = next
            return true
        } catch (bad: HttpDataSource.InvalidResponseCodeException) {
            val size = if (bad.responseCode == 416) {
                bad.headerFields.entries.firstOrNull {
                    it.key.equals("content-range", ignoreCase = true)
                }?.value?.firstOrNull()?.let {
                    RANGE_NOT_SATISFIABLE.find(it)?.groupValues?.get(1)?.toLongOrNull()
                }
            } else null
            runCatching { next.close() }
            if (size != null && offset >= size) {
                knownTotal = size
                return false
            }
            throw bad
        } catch (failure: Throwable) {
            runCatching { next.close() }
            throw failure
        }
    }

    override fun read(buffer: ByteArray, readOffset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        check(active) { "Source not opened" }
        if (completed || requestedRemaining == 0L) return C.RESULT_END_OF_INPUT
        while (true) {
            if (inner == null && !openNextChunk()) {
                completed = true
                return C.RESULT_END_OF_INPUT
            }
            val upstream = inner ?: continue
            val toRead = minOf(readLength.toLong(), chunkRemaining).toInt()
            if (toRead <= 0) {
                upstream.close()
                inner = null
                continue
            }
            val count = upstream.read(buffer, readOffset, toRead)
            if (count == C.RESULT_END_OF_INPUT) {
                // The server delivered less data than its declared range.
                // Do not silently join disjoint pieces of compressed audio.
                if (chunkRemaining > 0 &&
                    (knownTotal == C.LENGTH_UNSET.toLong() || offset < knownTotal)) {
                    throw EOFException("CDN truncated a bounded audio range")
                }
                upstream.close()
                inner = null
                continue
            }
            offset += count
            chunkRemaining -= count
            if (requestedRemaining != C.LENGTH_UNSET.toLong()) requestedRemaining -= count
            if (chunkRemaining == 0L) {
                upstream.close()
                inner = null
            }
            return count
        }
    }

    override fun getUri(): Uri? = inner?.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    override fun close() {
        active = false
        completed = true
        spec = null
        val closing = inner
        inner = null
        closing?.close()
        responseHeaders = emptyMap()
    }
}
