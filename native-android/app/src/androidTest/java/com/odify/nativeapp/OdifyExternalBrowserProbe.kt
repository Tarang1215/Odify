package com.odify.nativeapp

import android.content.ComponentName
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Capability probe against the installed app's public Android API. No auth bypass. */
class OdifyExternalBrowserProbe {
    @Test fun probePublicBrowserAndPlayFromUri() {
        val test = InstrumentationRegistry.getInstrumentation()
        val context = test.targetContext
        val ready = CountDownLatch(1)
        val result = JSONObject().put("connected", false)
        var browser: MediaBrowser? = null
        var controller: MediaController? = null
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            test.runOnMainSync {
                browser = MediaBrowser(context, ComponentName("app.morphe.android.youtube",
                    "com.google.android.apps.youtube.app.extensions.mediabrowser.impl.MainAppMediaBrowserService"),
                    object : MediaBrowser.ConnectionCallback() {
                        override fun onConnected() {
                            controller = MediaController(context, browser!!.sessionToken)
                            result.put("connected", true).put("actions", controller?.playbackState?.actions ?: 0L)
                            ready.countDown()
                        }
                        override fun onConnectionFailed() { result.put("connection", "denied");ready.countDown() }
                        override fun onConnectionSuspended() { result.put("connection", "suspended");ready.countDown() }
                    }, null)
                runCatching { browser!!.connect() }.onFailure {
                    result.put("connection", it.javaClass.simpleName);ready.countDown()
                }
            }
            if (!ready.await(8, TimeUnit.SECONDS)) result.put("connection", "timeout")
            val uri = InstrumentationRegistry.getArguments().getString("videoUri")
            if (result.optBoolean("connected") && uri != null &&
                ((controller?.playbackState?.actions ?: 0L) and PlaybackState.ACTION_PLAY_FROM_URI) != 0L) {
                val canonical = OdifyAudioRequestPolicy.youtubeUrl(uri)
                test.runOnMainSync { controller!!.transportControls.playFromUri(Uri.parse(canonical), null) }
                Thread.sleep(3000)
                result.put("stateAfter", controller?.playbackState?.state)
                    .put("titleAfter", controller?.metadata?.description?.title)
                    .put("activityResumed", scenario.state == androidx.lifecycle.Lifecycle.State.RESUMED)
            }
        } finally {
            test.runOnMainSync { browser?.disconnect() }
            scenario.close()
            File(context.filesDir, "external_browser_probe.json").writeText(result.toString(2))
        }
    }
}
