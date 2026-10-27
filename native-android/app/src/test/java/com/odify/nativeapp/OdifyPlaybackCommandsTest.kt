package com.odify.nativeapp

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class OdifyPlaybackCommandsTest {
    @Test fun bufferingAndFocusSuppressionKeepThePauseActionAvailable() {
        assertTrue(OdifyPlaybackCommands.hasPlayIntent(true,Player.STATE_BUFFERING))
        assertTrue(OdifyPlaybackCommands.hasPlayIntent(true,Player.STATE_READY))
        assertFalse(OdifyPlaybackCommands.hasPlayIntent(false,Player.STATE_BUFFERING))
        assertFalse(OdifyPlaybackCommands.hasPlayIntent(true,Player.STATE_ENDED))
        assertFalse(OdifyPlaybackCommands.hasPlayIntent(true,Player.STATE_IDLE))
        assertFalse(OdifyPlaybackCommands.hasPlayIntent(true,Player.STATE_READY,true))
    }
    @Test fun metadataFromADownloadedCopyDoesNotSwitchTheSelectedYoutubeTransport() {
        val downloaded=OdifySong("youtube:abcdefghijk","Messy","ROSÉ","F1","/private/song.m4a",
            sourceUri="file:///private/song.m4a",artworkPath="/official.jpg",durationMs=180000)
        val streamed=downloaded.forYoutubePlayback("abcdefghijk")
        assertTrue(streamed.isYoutubeStream)
        assertEquals(downloaded.id,streamed.id);assertEquals(downloaded.title,streamed.title)
        assertEquals(downloaded.artist,streamed.artist);assertEquals(downloaded.artworkPath,streamed.artworkPath)
        assertEquals(180000L,streamed.durationMs);assertEquals("",streamed.filePath);assertNull(streamed.sourceUri)
        assertEquals("/private/song.m4a",downloaded.filePath)
    }
    @Test fun replacingDriveAudioStillKeepsItsOfficialPresentationAndIdentity() {
        val original=OdifySong("gdrive:original","Song","Artist","Album","/old.mp3",artworkPath="/art.jpg")
        val replacement=original.forYoutubePlayback("abcdefghijk")
        assertEquals(original.id,replacement.id);assertEquals(original.album,replacement.album)
        assertEquals(original.artworkPath,replacement.artworkPath);assertTrue(replacement.isYoutubeStream)
    }
    @Test fun restoredSeekingKeepsBoundsAndDoesNotCreateAnEngineBeforeUserPlayback() {
        assertNull(OdifyYoutubeRuntime.service)
        val previous=OdifyYoutubeRuntime.session.value
        try {
            OdifyYoutubeRuntime.session.value=YoutubeSessionState(YoutubeVideo("abcdefghijk","Song","Artist",""),
                playback=YoutubeWebState(position=42000,duration=180000,ready=true),playIntent=false)
            OdifyYoutubeRuntime.command("odifySeek(999)")
            assertEquals(180000L,OdifyYoutubeRuntime.session.value.playback.position)
            OdifyYoutubeRuntime.command("odifySeek(-1)")
            assertEquals(0L,OdifyYoutubeRuntime.session.value.playback.position)
            OdifyYoutubeRuntime.command("odifySeek(1e309)")
            assertEquals(0L,OdifyYoutubeRuntime.session.value.playback.position)
            OdifyYoutubeRuntime.command("odifySeek(45);odifyPlay()")
            assertEquals(45000L,OdifyYoutubeRuntime.session.value.playback.position)
            assertTrue(OdifyYoutubeRuntime.session.value.playIntent);assertNull(OdifyYoutubeRuntime.service)
            OdifyYoutubeRuntime.command("odifyPause()")
            assertFalse(OdifyYoutubeRuntime.session.value.playIntent)
        } finally {OdifyYoutubeRuntime.session.value=previous}
    }
}
