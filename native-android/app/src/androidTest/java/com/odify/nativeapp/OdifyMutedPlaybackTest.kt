package com.odify.nativeapp

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.SystemClock
import android.accessibilityservice.AccessibilityService
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.*

/** Explicit opt-in REAL playback test. User authorized muted office playback on
 * 2026-10-27. Never include in the office-safe fixture suite or run at volume >0. */
class OdifyMutedPlaybackTest {
    private val test=InstrumentationRegistry.getInstrumentation()
    private val context get()=test.targetContext
    private val audio get()=context.getSystemService(AudioManager::class.java)
    private fun main(block:()->Unit)=test.runOnMainSync(block)
    private fun muted(){assertEquals("Stop: muted playback permission requires volume zero",0,audio.getStreamVolume(AudioManager.STREAM_MUSIC))}
    private fun until(label:String,timeout:Long=20000,predicate:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<deadline) {
            muted();var ready=false;main {ready=predicate()};if(ready)return
            SystemClock.sleep(100)
        }
        val details=java.util.concurrent.atomic.AtomicReference<String>()
        val signal=java.util.concurrent.CountDownLatch(1)
        main {val view=OdifyYoutubeRuntime.view.value
            if(view==null)signal.countDown() else view.evaluateJavascript("JSON.stringify({snapshot:window.odifySnapshot&&window.odifySnapshot(),pending:window.pendingCommand,wants:window.wantsPlayback})") {
                details.set(it);signal.countDown()
            }}
        signal.await(2,java.util.concurrent.TimeUnit.SECONDS)
        error("Timed out: $label; ${OdifyYoutubeRuntime.session.value.playback}; script=${details.get()}")
    }
    private fun controllers()=context.getSystemService(MediaSessionManager::class.java)
        .getActiveSessions(null).filter {it.packageName==context.packageName}
    private fun capture(name:String) {
        val image=test.uiAutomation.takeScreenshot() ?: return
        File(context.cacheDir,"muted-$name.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }
    @Test fun realYoutubeWidgetControlsAndSystemMetadataAtZeroVolume() {
        muted()
        val store=OdifyStreamingLibrary(context)
        val available=store.all().distinctBy {store.video(it).videoId}.map {
            it.copy(filePath="",sourceUri=null,playbackVideoId=store.video(it).videoId)
        }
        val first=available.firstOrNull {it.title.equals("Messy",true)} ?: requireNotNull(available.firstOrNull())
        val next=requireNotNull(available.firstOrNull {store.video(it).videoId!=store.video(first).videoId})
        val report=JSONArray()
        val traceScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        traceScope.launch {OdifyYoutubeRuntime.session.collect {s ->
            if(report.length()<300)report.put(JSONObject().put("step","state").put("at",SystemClock.elapsedRealtime())
                .put("intent",s.playIntent).put("playing",s.playback.playing).put("buffering",s.playback.buffering)
                .put("position",s.playback.position).put("ready",s.playback.ready))
        }}
        test.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                main {OdifyYoutubeRuntime.play(context,store.video(first),listOf(next),presentation=first)}
                until("first video playing") {OdifyYoutubeRuntime.session.value.playback.playing}
                until("official media description",5000) {
                    val current=OdifyYoutubeRuntime.session.value
                    controllers().firstOrNull()?.metadata?.description?.title?.toString()==current.presentation?.title
                }
                main {
                    report.put(JSONObject().put("step","first").put("expected",OdifyYoutubeRuntime.session.value.presentation?.title)
                        .put("sessions",JSONArray(controllers().map {c ->JSONObject().put("title",c.metadata?.description?.title?.toString())
                            .put("artist",c.metadata?.description?.subtitle?.toString()).put("state",c.playbackState?.state).put("openable",c.sessionActivity!=null)})))
                }
                test.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);SystemClock.sleep(700);capture("youtube-home")
                test.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);SystemClock.sleep(700);capture("youtube-quick-settings")
                fun widget(command:Int) {main {
                    val track=OdifyWidgetPlayback.read(context)
                    report.put(JSONObject().put("step","widget-command").put("control",command).put("trackPlaying",track.playing)
                        .put("intent",OdifyYoutubeRuntime.session.value.playIntent).put("at",SystemClock.elapsedRealtime()))
                    val views=OdifyWidgetPlayback.views(context,track,true,compact=true)
                        .apply(context,android.widget.FrameLayout(context))
                    assertTrue(views.findViewById<android.view.View>(command).performClick())
                }}
                widget(R.id.widget_toggle)
                until("widget pause",5000) {!OdifyYoutubeRuntime.session.value.playback.playing}
                widget(R.id.widget_toggle)
                until("widget resume",5000) {OdifyYoutubeRuntime.session.value.playback.playing}
                widget(R.id.widget_next)
                until("widget next") {val v=OdifyYoutubeRuntime.session.value;v.video?.videoId==store.video(next).videoId && v.playback.playing}
                until("next metadata",5000) {controllers().firstOrNull()?.metadata?.description?.title?.toString()==OdifyYoutubeRuntime.session.value.presentation?.title}
                main {report.put(JSONObject().put("step","next").put("title",controllers().firstOrNull()?.metadata?.description?.title?.toString()))
                    OdifyYoutubeRuntime.command("odifySeek(0)")}
                until("seek before previous",5000){OdifyYoutubeRuntime.session.value.playback.position<2000}
                widget(R.id.widget_previous)
                until("widget previous") {val v=OdifyYoutubeRuntime.session.value;v.video?.videoId==store.video(first).videoId && v.playback.playing}
                main {OdifyYoutubeRuntime.command("odifyPause()")}
                until("final pause",5000){!OdifyYoutubeRuntime.session.value.playback.playing}
                File(context.cacheDir,"muted-playback-report.json").writeText(report.toString(2))
                assertEquals(0,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            }
        } finally {
            traceScope.cancel()
            runCatching {main {report.put(JSONObject().put("step","final-diagnostics").put("sessions",JSONArray(controllers().map {c ->
                JSONObject().put("title",c.metadata?.description?.title?.toString()).put("state",c.playbackState?.state)
                    .put("openable",c.sessionActivity!=null)})))}
                File(context.cacheDir,"muted-playback-report.json").writeText(report.toString(2))}
            main {OdifyYoutubeRuntime.stop(context)}
            android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("cmd statusbar collapse")).use {it.readBytes()}
            test.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun singleChoiceContinuesAfterItsEndAndRecoversABackgroundRendererAtZeroVolume() {
        muted()
        val store=OdifyStreamingLibrary(context)
        val original=requireNotNull(store.all().firstOrNull())
        val first=original.copy(filePath="",sourceUri=null,playbackVideoId=store.video(original).videoId)
        val nativeFuture=androidx.media3.session.MediaController.Builder(context,
            androidx.media3.session.SessionToken(context,android.content.ComponentName(context,OdifyPlaybackService::class.java))).buildAsync()
        val native=nativeFuture.get(10,java.util.concurrent.TimeUnit.SECONDS)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                main {OdifyYoutubeRuntime.play(context,store.video(first),emptyList(),presentation=first)}
                until("single choice playing") {OdifyYoutubeRuntime.session.value.playback.playing}
                until("automatic following queue",10000) {OdifyYoutubeRuntime.session.value.following.isNotEmpty()}
                test.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                if(android.os.Build.VERSION.SDK_INT>=29) {
                    var previous:android.webkit.WebView?=null
                    main {previous=OdifyYoutubeRuntime.view.value
                        assertTrue("Renderer termination request",requireNotNull(previous?.webViewRenderProcess).terminate())}
                    until("recover renderer without killing host",30000) {
                        OdifyYoutubeRuntime.view.value!=null && OdifyYoutubeRuntime.view.value!==previous &&
                            OdifyYoutubeRuntime.session.value.playback.playing
                    }
                    assertEquals(store.video(first).videoId,OdifyYoutubeRuntime.session.value.video?.videoId)
                }
                until("duration available",10000) {OdifyYoutubeRuntime.session.value.playback.duration>5000}
                main {
                    val remaining=OdifyYoutubeRuntime.session.value.playback.duration/1000.0-1.0
                    OdifyYoutubeRuntime.command("odifySeek($remaining)")
                }
                until("natural next after one selected song",30000) {
                    val state=OdifyYoutubeRuntime.session.value
                    // Recommendations may legitimately hand off to the native
                    // engine; either engine must move past the selected song.
                    state.video?.videoId!=store.video(first).videoId &&
                        (state.playback.playing || state.video==null && native.isPlaying && native.currentMediaItem?.mediaId!=first.id)
                }
                assertEquals(0,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            }
        } finally {
            main {OdifyYoutubeRuntime.stop(context)}
            // A natural transition may have selected a local/Drive recording.
            main {native.pause();native.clearMediaItems();native.release()}
        }
    }
}
