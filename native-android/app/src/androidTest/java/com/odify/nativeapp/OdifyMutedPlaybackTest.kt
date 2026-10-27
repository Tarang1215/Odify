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
        error("Timed out: $label; ${OdifyYoutubeRuntime.session.value.playback}")
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
            main {OdifyYoutubeRuntime.stop(context)}
            test.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            test.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
