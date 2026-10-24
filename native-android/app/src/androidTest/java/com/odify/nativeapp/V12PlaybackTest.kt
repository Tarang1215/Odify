package com.odify.nativeapp

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Runs against real Media3 on Android, with generated audio and no user accounts. */
@RunWith(AndroidJUnit4::class)
class V12PlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun eventually(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            var done = false
            main { done = condition() }
            if (done) return
            Thread.sleep(80)
        }
        fail(message)
    }
    private fun wave(): ByteArray {
        val samples = 44100 * 20
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2)
            put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(44100); putInt(88200); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
            repeat(samples) { putShort((kotlin.math.sin(it * 2.0 * Math.PI * 440 / 44100) * 1000).toInt().toShort()) }
        }.array()
    }

    @Test fun realServicePlaysFileAndContentUrisAndKeepsDuplicateQueueIdentity() {
        val audio = File(context.cacheDir, "v12-instrumentation.wav").apply { writeBytes(wave()) }
        val content = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "Odify V12 test.wav")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }) ?: error("Could not create test media")
        context.contentResolver.openOutputStream(content)!!.use { it.write(audio.readBytes()) }
        context.contentResolver.update(content, ContentValues().apply {
            put(MediaStore.Audio.Media.IS_PENDING, 0)
        }, null, null)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
        main {
            future = MediaController.Builder(context, SessionToken(context,
                ComponentName(context, OdifyPlaybackService::class.java))).buildAsync()
        }
        val player = future.get(20, TimeUnit.SECONDS)
        try {
            val local = OdifySong("test:file", "한국어 제목", "日本語アーティスト", "앨범", audio.path)
            val linked = local.copy(id = "localfolder:test", sourceUri = content.toString())
            main {
                player.setMediaItems(listOf(local.mediaItem(), local.mediaItem(), linked.mediaItem()))
                player.prepare(); player.play()
            }
            eventually("File playback did not advance") { player.isPlaying && player.currentPosition > 250 }
            main {
                val entries = (0..2).map {
                    player.getMediaItemAt(it).mediaMetadata.extras!!.getString("odify.queueEntry")
                }
                assertEquals(3, entries.toSet().size)
                val first = entries[0]
                player.moveMediaItem(0, 2)
                assertEquals(first, player.getMediaItemAt(2).mediaMetadata.extras!!.getString("odify.queueEntry"))
                assertEquals(2, player.currentMediaItemIndex)
                assertEquals(listOf(local.id, linked.id, local.id), (0..2).map { player.getMediaItemAt(it).mediaId })
                player.seekToDefaultPosition(1)
            }
            eventually("content:// playback did not advance") {
                player.currentMediaItem?.mediaId == linked.id && player.isPlaying && player.currentPosition > 250
            }
            // Home then screen off: audio must keep advancing in the service.
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.uiAutomation.executeShellCommand("input keyevent 223").close()
            var before = 0L
            main { before = player.currentPosition }
            Thread.sleep(1500)
            main {
                assertTrue("Background/locked playback stopped", player.isPlaying)
                assertTrue("Playback position stopped", player.currentPosition > before + 700)
                assertNull(player.playerError)
            }
        } finally {
            instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
            main { player.pause(); player.clearMediaItems(); player.release() }
            scenario.close()
            context.stopService(Intent(context, OdifyPlaybackService::class.java))
            context.contentResolver.delete(content, null, null)
            audio.delete()
        }
    }

    @Test fun drive401RefreshesOnceAndOtherHostsNeverReceiveDriveToken() {
        class Fake : DataSource {
            val opened = mutableListOf<DataSpec>()
            var reject = true
            var alwaysReject = false
            override fun addTransferListener(listener: TransferListener) {}
            override fun getUri(): Uri? = opened.lastOrNull()?.uri
            override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
            override fun close() {}
            override fun open(spec: DataSpec): Long {
                opened += spec
                if (reject || alwaysReject) throw HttpDataSource.InvalidResponseCodeException(
                    401, "Unauthorized", null, emptyMap(), spec, byteArrayOf())
                return 10
            }
        }
        val fake = Fake()
        var current = "expired-test-token"
        var invalidations = 0
        val source = OdifyDriveDataSource(fake, { current }, {
            assertEquals(current, it); invalidations++; current = "renewed-test-token"
            fake.reject = false
        })
        val spec = DataSpec.Builder()
            .setUri("https://www.googleapis.com/drive/v3/files/test?alt=media")
            .setPosition(20).setLength(40).build()
        assertEquals(10L, source.open(spec))
        assertEquals(1, invalidations)
        assertEquals("Bearer expired-test-token", fake.opened[0].httpRequestHeaders["Authorization"])
        assertEquals("Bearer renewed-test-token", fake.opened[1].httpRequestHeaders["Authorization"])
        assertEquals(20L, fake.opened[1].position)
        assertEquals(40L, fake.opened[1].length)
        source.open(DataSpec(Uri.parse("https://example.org/audio.mp3")))
        assertFalse(fake.opened.last().httpRequestHeaders.containsKey("Authorization"))
        fake.alwaysReject = true
        try { source.open(spec); fail("Repeated 401 must propagate") }
        catch (_: HttpDataSource.InvalidResponseCodeException) { /* bounded retry */ }
        assertEquals(2, invalidations)
    }

    @Test fun playlistEditsPersistAndConservativeMatchingPreservesCjk() {
        val playlists = OdifyPlaylists(context)
        val list = playlists.create("V12 test ${System.nanoTime()}")
        try {
            assertTrue(playlists.addSong(list.id, "localfolder:one"))
            assertFalse(playlists.addSong(list.id, "localfolder:one"))
            assertEquals(listOf("localfolder:one"), OdifyPlaylists(context).all().first { it.id == list.id }.songIds)
            playlists.removeSong(list.id, "localfolder:one")
            assertTrue(OdifyPlaylists(context).all().first { it.id == list.id }.songIds.isEmpty())
            val track = OdifySong("localfolder:one", "밤편지", "아이유", "", "")
            assertNull(OdifyYouTubeMatcher.find(
                YoutubeVideo("abcdefghijk", "아이유 - 밤편지 (Official Audio)", "", ""), listOf(track), emptyList()))
            val reviewed = MasterTrack("ODF000001", "2026", "", "아이유", "밤편지", "",
                "https://www.youtube.com/watch?v=abcdefghijk", true, "", "", "studio")
            assertEquals(track, OdifyYouTubeMatcher.find(
                YoutubeVideo("abcdefghijk", "아이유 - 밤편지 (Official Audio)", "", ""), listOf(track), listOf(reviewed)))
            assertNull(OdifyYouTubeMatcher.find(
                YoutubeVideo("abcdefghijk", "아이유 - 밤편지 (Live)", "", ""), listOf(track), emptyList()))
            assertEquals("米津玄師", OdifyYouTubeMatcher.norm("米津 玄師"))
        } finally { playlists.delete(list.id) }
    }
}
