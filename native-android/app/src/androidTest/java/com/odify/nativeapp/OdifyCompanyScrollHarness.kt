package com.odify.nativeapp

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Opt-in real-library gesture measurement. Installs a paused presentation only:
 * never starts a playback service, WebView, prepare(), play(), or audio decoding.
 * The companion desktop script drives only whitelisted non-playback controls. */
class OdifyCompanyScrollHarness {
    @Test fun holdPausedHomeForScrollMeasurementWithoutStartingPlayback() {
        val test=InstrumentationRegistry.getInstrumentation();val context=test.targetContext
        val prefs=context.getSharedPreferences("odify_native_ui",0)
        val history=prefs.getString("history",null)
        val stop=File(context.cacheDir,"company-scroll-stop");stop.delete()
        val ready=File(context.cacheDir,"company-scroll-ready");ready.delete()
        val streaming=OdifyStreamingLibrary(context)
        val preferred=history.orEmpty().split("|").firstNotNullOfOrNull(streaming::find)
            ?: streaming.all().firstOrNull()
        val song=requireNotNull(preferred){"A saved presentation is needed for the paused benchmark"}
        assertNull(OdifyYoutubeRuntime.service)
        test.runOnMainSync {
            OdifyYoutubeRuntime.session.value=YoutubeSessionState(streaming.video(song),
                playback=YoutubeWebState(ready=true),presentation=song)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                SystemClock.sleep(1800);ready.writeText("paused-presentation-only")
                val deadline=SystemClock.elapsedRealtime()+150000
                while(!stop.isFile && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100)
                assertTrue("Desktop measurement did not finish",stop.isFile)
                assertNull("Scrolls must not start a playback service",OdifyYoutubeRuntime.service)
            }
        } finally {
            test.runOnMainSync {OdifyYoutubeRuntime.session.value=YoutubeSessionState()}
            prefs.edit().apply {if(history==null)remove("history") else putString("history",history)}.commit()
            ready.delete();stop.delete()
        }
    }
}
