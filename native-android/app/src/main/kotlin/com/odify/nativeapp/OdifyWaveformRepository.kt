package com.odify.nativeapp

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Real audio waveform based on decoded PCM, never on pseudo-random/sine bars.
 *
 * Decode only after opening Now Playing; subsample PCM frames to bound CPU work,
 * persist 96 bins keyed by the SHA-256 track id, then reuse the cache.
 * A failed decoder returns emptyList -> simple seek line rather than fake waveform.
 */
object OdifyWaveformRepository {
    private const val BAR_COUNT = 96
    private const val DECODE_TIMEOUT_US = 10_000L
    private val memory = java.util.concurrent.ConcurrentHashMap<String, List<Float>>()

    /** Synchronous in-memory read only; never blocks Compose on filesystem I/O. */
    fun peek(id: String): List<Float> = memory[id] ?: emptyList()

    private fun remember(id: String, result: List<Float>) {
        if (result.size != BAR_COUNT) return
        if (memory.size > 256) memory.clear()
        memory[id] = result
    }

    suspend fun load(context: Context, song: OdifySong): List<Float> =
        withContext(Dispatchers.IO) {
            memory[song.id]?.let { return@withContext it }
            val cacheDir = File(context.filesDir, "odify_waveforms_v1").apply { mkdirs() }
            val cache = File(cacheDir, "${song.id}.json")
            try {
                if (cache.exists()) {
                    val json = JSONArray(cache.readText())
                    if (json.length() == BAR_COUNT) {
                        val cached = (0 until json.length()).map { i ->
                            json.optDouble(i).toFloat().coerceIn(0f, 1f)
                        }
                        remember(song.id, cached)
                        return@withContext cached
                    }
                }
                val values = analyze(context, song)
                remember(song.id, values)
                if (values.size == BAR_COUNT) {
                    val payload = JSONArray()
                    values.forEach { payload.put(it.toDouble()) }
                    val tmp = File(cacheDir, "${song.id}.tmp")
                    tmp.writeText(payload.toString())
                    if (!tmp.renameTo(cache)) {
                        cache.writeText(payload.toString())
                        tmp.delete()
                    }
                }
                values
            } catch (_: Exception) {
                emptyList()
            }
        }

    private fun analyze(context: Context, song: OdifySong): List<Float> {
        val file = if (song.isCloud) null else File(song.filePath)
        if (!song.isCloud && file?.isFile != true) return emptyList()
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            if (song.isCloud) {
                extractor.setDataSource(
                    context, Uri.parse(song.sourceUri), null
                )
            } else {
                extractor.setDataSource(file!!.absolutePath)
            }
            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrack = i
                    break
                }
            }
            if (audioTrack < 0) return emptyList()
            extractor.selectTrack(audioTrack)
            val inputFormat = extractor.getTrackFormat(audioTrack)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            val durationUs = (inputFormat.getLongOrNull(MediaFormat.KEY_DURATION)
                ?: song.durationMs * 1_000L).coerceAtLeast(1L)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val sums = DoubleArray(BAR_COUNT)
            val counts = IntArray(BAR_COUNT)
            var inputDone = false
            var outputDone = false
            var idleCount = 0
            val info = MediaCodec.BufferInfo()
            var loops = 0
            while (!outputDone && loops++ < 160_000 && idleCount < 500) {
                var worked = false
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(DECODE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)
                        if (input != null) {
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputIndex, 0, size,
                                    extractor.sampleTime.coerceAtLeast(0L), 0
                                )
                                extractor.advance()
                            }
                        }
                        worked = true
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, DECODE_TIMEOUT_US)
                when {
                    outputIndex >= 0 -> {
                        worked = true
                        if (info.size > 0) {
                            val format = decoder.outputFormat
                            val encoding = format.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING)
                                ?: android.media.AudioFormat.ENCODING_PCM_16BIT
                            if (encoding == android.media.AudioFormat.ENCODING_PCM_16BIT) {
                                val sampleRate = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE)
                                    ?: 44100
                                val channels = (format.getIntegerOrNull(
                                    MediaFormat.KEY_CHANNEL_COUNT) ?: 2).coerceAtLeast(1)
                                val output = decoder.getOutputBuffer(outputIndex)
                                if (output != null) {
                                    output.position(info.offset)
                                    output.limit(info.offset + info.size)
                                    val shorts = output.slice().order(ByteOrder.LITTLE_ENDIAN)
                                        .asShortBuffer()
                                    val frames = shorts.remaining() / channels
                                    // Sample at most once per 64 frames (~700 samples/s at 44kHz).
                                    var frame = 0
                                    while (frame < frames) {
                                        val us = info.presentationTimeUs +
                                            frame.toLong() * 1_000_000L / sampleRate
                                        val bin = ((us.toDouble() / durationUs) * BAR_COUNT)
                                            .toInt().coerceIn(0, BAR_COUNT - 1)
                                        val absolute = abs(shorts.get(frame * channels).toInt())
                                        sums[bin] += absolute.toDouble() / 32768.0
                                        counts[bin]++
                                        frame += 64
                                    }
                                }
                            }
                        }
                        outputDone =
                            (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> worked = true
                }
                idleCount = if (worked) 0 else idleCount + 1
            }
            if (counts.sum() == 0) return emptyList()
            val means = (0 until BAR_COUNT).map { i ->
                if (counts[i] == 0) 0.0 else sums[i] / counts[i]
            }
            // Avoid having a single transient peak compress the entire display.
            val sorted = means.sorted()
            val reference = max(0.001, sorted[(BAR_COUNT * 0.92).toInt()])
            return means.map { (it / reference).toFloat().coerceIn(0f, 1f) }
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
        }
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun MediaFormat.getLongOrNull(key: String): Long? =
        if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null
}
