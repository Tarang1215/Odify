package com.odify.nativeapp

import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit on-device network test using the user's existing granted accounts. */
class OdifyYoutubeBackgroundTest {
    @Test fun playsInBackgroundAndHandsOffToOwnedDriveAudio() {
        val test=InstrumentationRegistry.getInstrumentation()
        val context=test.targetContext
        val proof=JSONObject()
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        var controller:MediaController?=null
        fun await(timeout:Long=30_000,condition:()->Boolean) {
            val end=System.currentTimeMillis()+timeout
            while(!condition() && System.currentTimeMillis()<end) Thread.sleep(200)
            assertTrue("Timed out: $proof",condition())
        }
        try {
            val restored=CountDownLatch(1)
            scenario.onActivity {activity ->
                if(OdifyYoutubeAuth.connected) restored.countDown()
                else OdifyYoutubeAuth.restore(activity){restored.countDown()}
            }
            assertTrue("Existing YouTube grant must restore without new consent",restored.await(12,TimeUnit.SECONDS))
            val results=runBlocking {OdifyYoutubeLibrary(context).search("NewJeans Hype Boy lyrics")}
            val video=results.firstOrNull {it.channel.contains("Jaeguchi",true)} ?: OdifyYoutubeQuickPlay.first(results)
                ?: error("No playback candidate")
            val drive=DriveCatalog(context).tracks().firstOrNull {it.id.startsWith("gdrive:") && it.durationMs>60_000}
                ?: error("Existing owned Drive song required")
            proof.put("videoId",video.videoId).put("driveId",drive.id)
            scenario.onActivity {OdifyYoutubeRuntime.play(it,video,listOf(drive))}
            await {val s=OdifyYoutubeRuntime.session.value.playback;s.playing && s.position>1000 && s.duration>10_000}
            val before=OdifyYoutubeRuntime.session.value.playback.position
            test.uiAutomation.executeShellCommand("input keyevent 3").close()
            Thread.sleep(5000)
            val after=OdifyYoutubeRuntime.session.value.playback
            proof.put("backgroundBefore",before).put("backgroundAfter",after.position).put("backgroundPlaying",after.playing)
            assertTrue("Background playback stopped: $proof",after.playing && after.position>before+2000)
            val future=MediaController.Builder(context,SessionToken(context,ComponentName(context,OdifyPlaybackService::class.java))).buildAsync()
            controller=future.get(12,TimeUnit.SECONDS)
            test.runOnMainSync {OdifyYoutubeRuntime.command("odifySeek(${(after.duration-1500).coerceAtLeast(0)/1000.0})")}
            var nativePlaying=false
            var nativeId:String?=null
            await(35_000) {
                test.runOnMainSync {nativePlaying=controller?.isPlaying==true;nativeId=controller?.currentMediaItem?.mediaId}
                nativePlaying && nativeId==drive.id && OdifyYoutubeRuntime.session.value.video==null
            }
            proof.put("handoffPlaying",nativePlaying).put("handoffId",nativeId)
        } finally {
            File(context.filesDir,"youtube_background_test.json").writeText(proof.toString(2))
            test.runOnMainSync {controller?.pause();controller?.release();OdifyYoutubeRuntime.stop(context)}
            scenario.close()
        }
    }
}
