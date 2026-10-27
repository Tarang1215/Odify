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
    private fun controllers():List<android.media.session.MediaController> {
        // Privileged identity is needed only for enumerating sessions, so keep
        // it scoped to the query rather than the whole playback test.
        test.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
        return try {context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(null).filter {it.packageName==context.packageName}}
        finally {test.uiAutomation.dropShellPermissionIdentity()}
    }
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
        val nativeFuture=androidx.media3.session.MediaController.Builder(context,
            androidx.media3.session.SessionToken(context,android.content.ComponentName(context,OdifyPlaybackService::class.java))).buildAsync()
        val native=nativeFuture.get(10,java.util.concurrent.TimeUnit.SECONDS)
        val report=JSONArray()
        val traceScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        traceScope.launch {OdifyYoutubeRuntime.session.collect {s ->
            if(report.length()<300)report.put(JSONObject().put("step","state").put("at",SystemClock.elapsedRealtime())
                .put("intent",s.playIntent).put("playing",s.playback.playing).put("buffering",s.playback.buffering)
                .put("position",s.playback.position).put("ready",s.playback.ready))
        }}
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                main {native.pause();native.clearMediaItems();OdifyYoutubeRuntime.play(context,store.video(first),listOf(next),presentation=first)}
                until("first video makes real progress") {OdifyYoutubeRuntime.session.value.playback.let {it.playing && it.position>1200}}
                until("official media description",5000) {
                    val current=OdifyYoutubeRuntime.session.value
                    controllers().firstOrNull()?.metadata?.description?.title?.toString()==current.presentation?.title
                }
                main {
                    report.put(JSONObject().put("step","first").put("expected",OdifyYoutubeRuntime.session.value.presentation?.title)
                        .put("sessions",JSONArray(controllers().map {c ->JSONObject().put("title",c.metadata?.description?.title?.toString())
                            .put("artist",c.metadata?.description?.subtitle?.toString()).put("state",c.playbackState?.state).put("openable",c.sessionActivity!=null)})))
                }
                val beforeHome=OdifyYoutubeRuntime.session.value.playback.position
                test.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                until("background playback progress",10000) {OdifyYoutubeRuntime.session.value.playback.let {it.playing && it.position>beforeHome+1000}}
                capture("youtube-home")
                test.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);SystemClock.sleep(700);capture("youtube-quick-settings")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("cmd statusbar collapse")).use {it.readBytes()}
                test.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                val info=test.uiAutomation.serviceInfo
                info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                test.uiAutomation.serviceInfo=info
                fun launcherRoot():android.view.accessibility.AccessibilityNodeInfo {
                    val deadline=SystemClock.elapsedRealtime()+5000
                    while(SystemClock.elapsedRealtime()<deadline) {
                        if(android.os.Build.VERSION.SDK_INT>=34)test.uiAutomation.clearCache()
                        // A Samsung media chip can remain the accessibility
                        // active window while the launcher has input focus.
                        val focused=test.uiAutomation.windows.firstOrNull {it.isFocused &&
                            it.root?.packageName?.toString()=="com.sec.android.app.launcher"}?.root
                        if(focused!=null)return focused
                        val root=test.uiAutomation.rootInActiveWindow
                        if(root?.packageName?.toString()=="com.sec.android.app.launcher")return root
                        SystemClock.sleep(100)
                    }
                    error("Launcher did not become ready after closing Quick Settings")
                }
                fun widget(command:Int) {
                    val id="${context.packageName}:id/${context.resources.getResourceEntryName(command)}"
                    fun control(node:android.view.accessibility.AccessibilityNodeInfo):android.view.accessibility.AccessibilityNodeInfo? {
                        if(node.viewIdResourceName==id)return node
                        for(index in 0 until node.childCount)node.getChild(index)?.let {child ->
                            control(child)?.let {return it}
                        }
                        return null
                    }
                    var root=launcherRoot()
                    // Resolve foreign RemoteViews IDs from node metadata; the
                    // launcher's own Resources cannot resolve our resource IDs.
                    var target=control(root)
                    if(target==null) {
                        val metrics=context.resources.displayMetrics
                        val line="input swipe ${(metrics.widthPixels*.86).toInt()} ${(metrics.heightPixels*.42).toInt()} ${(metrics.widthPixels*.17).toInt()} ${(metrics.heightPixels*.42).toInt()} 350"
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(line)).use {it.readBytes()}
                        SystemClock.sleep(400);root=launcherRoot()
                        target=control(root)
                    }
                    val button=requireNotNull(target){"Place Odify 4x1 widget on one of the first two home pages"}
                    val track=OdifyWidgetPlayback.read(context)
                    report.put(JSONObject().put("step","widget-command").put("control",command).put("trackPlaying",track.playing)
                        .put("intent",OdifyYoutubeRuntime.session.value.playIntent).put("at",SystemClock.elapsedRealtime()))
                    assertTrue(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                }
                until("still playing before widget pause",5000){OdifyYoutubeRuntime.session.value.playback.playing}
                widget(R.id.widget_toggle)
                until("widget pause",5000) {OdifyYoutubeRuntime.session.value.let {!it.playIntent && !it.playback.playing}}
                SystemClock.sleep(600)
                widget(R.id.widget_next)
                until("paused next keeps its transport and pause") {val s=OdifyYoutubeRuntime.session.value
                    s.video?.videoId==store.video(next).videoId && s.playback.ready && !s.playback.buffering && !s.playback.playing && !s.playIntent}
                widget(R.id.widget_previous)
                until("paused previous stays paused") {val s=OdifyYoutubeRuntime.session.value
                    s.video?.videoId==store.video(first).videoId && s.playback.ready && !s.playback.buffering && !s.playback.playing && !s.playIntent}
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
            main {OdifyYoutubeRuntime.stop(context);native.pause();native.clearMediaItems();native.release()}
            android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("cmd statusbar collapse")).use {it.readBytes()}
            test.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun silentWave():File {
        val samples=16000
        val data=java.nio.ByteBuffer.allocate(44+samples*2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples*2)
        return File(context.cacheDir,"muted-qa-${System.nanoTime()}.wav").apply {writeBytes(data.array())}
    }

    @Test fun nativeEndCanRestartThroughTheSharedAppAndWidgetCommandAtZeroVolume() {
        muted();val file=silentWave();var player:androidx.media3.exoplayer.ExoPlayer?=null
        try {
            main {
                player=androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
                    prepare();play()
                }
            }
            until("silent native file ends",8000){player?.playbackState==androidx.media3.common.Player.STATE_ENDED}
            main {OdifyPlaybackCommands.toggle(requireNotNull(player))}
            until("ended native file restarts",5000){player?.isPlaying==true && requireNotNull(player).currentPosition in 50..899}
            main {OdifyPlaybackCommands.toggle(requireNotNull(player));assertFalse(requireNotNull(player).playWhenReady)}
        } finally {main {player?.release()};file.delete()}
    }

    @Test fun pausedMixedQueueNeverStartsAudioWhileSkippingAcrossEngines() {
        muted();val file=silentWave();val oldWidget=OdifyWidgetPlayback.read(context)
        val store=OdifyStreamingLibrary(context);val original=requireNotNull(store.all().firstOrNull())
        val first=original.forYoutubePlayback(store.video(original).videoId)
        val next=OdifySong("qa:paused-native","Silent QA","Odify QA","",file.absolutePath,durationMs=1000)
        val future=androidx.media3.session.MediaController.Builder(context,
            androidx.media3.session.SessionToken(context,android.content.ComponentName(context,OdifyPlaybackService::class.java))).buildAsync()
        val native=future.get(10,java.util.concurrent.TimeUnit.SECONDS)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                main {native.pause();native.clearMediaItems();OdifyYoutubeRuntime.play(context,store.video(first),listOf(next),presentation=first)}
                until("mixed queue first playing"){OdifyYoutubeRuntime.session.value.playback.playing}
                main {OdifyYoutubeRuntime.command("odifyPause()")};SystemClock.sleep(700)
                main {OdifyYoutubeRuntime.next(context)}
                until("paused native handoff",10000){OdifyYoutubeRuntime.session.value.video==null &&
                    native.currentMediaItem?.mediaId==next.id && native.playbackState==androidx.media3.common.Player.STATE_READY}
                main {assertFalse(native.playWhenReady);assertFalse(native.isPlaying);native.seekToPreviousMediaItem()}
                until("paused return to youtube"){val s=OdifyYoutubeRuntime.session.value
                    s.video?.videoId==store.video(first).videoId && s.playback.ready && !s.playback.buffering && !s.playIntent}
                SystemClock.sleep(700)
                main {assertFalse(OdifyYoutubeRuntime.session.value.playback.playing)}
            }
        } finally {
            main {OdifyYoutubeRuntime.stop(context);native.pause();native.clearMediaItems();native.release()
                OdifyWidgetPlayback.publish(context,oldWidget)}
            file.delete()
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
                until("single choice makes real progress") {OdifyYoutubeRuntime.session.value.playback.let {it.playing && it.position>1200}}
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
