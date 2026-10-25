package com.odify.nativeapp

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Collections

/** Same production mini/nav glass, fixed background and identical scripted
 * transitions. No media/network/user catalog work is included in the interval. */
class OdifyGlassFrameTest {
    @Test fun measureProductionGlassTransitionsAtPreferredHighRefreshRate() {
        val test=InstrumentationRegistry.getInstrumentation()
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        val compact=mutableStateOf(true)
        val phase=java.util.concurrent.atomic.AtomicReference("warmup")
        val timings=Collections.synchronizedList(ArrayList<Triple<String,Long,Long>>())
        val thread=HandlerThread("Odify-frame-metrics").apply {start()}
        var window:Window?=null
        var prior:android.view.WindowManager.LayoutParams?=null
        var refresh=0f
        val listener=Window.OnFrameMetricsAvailableListener {_,metrics,_ ->
            timings.add(Triple(phase.get(),metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)))
        }
        try {
            scenario.onActivity {activity ->
                window=activity.window
                prior=android.view.WindowManager.LayoutParams().apply {copyFrom(activity.window.attributes)}
                @Suppress("DEPRECATION") val display=activity.windowManager.defaultDisplay
                val current=display.mode
                val mode=display.supportedModes.filter {it.physicalWidth==current.physicalWidth &&
                    it.physicalHeight==current.physicalHeight && it.refreshRate<=120.1f}.maxByOrNull {it.refreshRate}
                activity.window.attributes=activity.window.attributes.apply {
                    preferredDisplayModeId=mode?.modeId ?: 0;preferredRefreshRate=mode?.refreshRate ?: 120f
                }
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContent {
                    MaterialTheme(typography=OdifyTypography,colorScheme=androidx.compose.material3.darkColorScheme()) {
                        OdifyGlassPreferencesProvider {
                            val backdrop=rememberOdifyBackdrop()
                            Box(Modifier.fillMaxSize().background(OdifyPalette.background)) {
                                Column(Modifier.fillMaxSize().recordOdifyBackdrop(backdrop)) {
                                    repeat(35) {i ->
                                        Row(Modifier.fillMaxWidth().height(32.dp).background(
                                            listOf(Color(0xFF194C58),Color(0xFF452D54),Color(0xFF464B30))[i%3])) {
                                            Text("Odify · Album artwork · $i",color=Color.White,modifier=Modifier.padding(4.dp))
                                        }
                                    }
                                }
                                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp).height(58.dp)
                                    .odifyGlass(backdrop,androidx.compose.foundation.shape.RoundedCornerShape(21.dp),Color.Cyan,navigation=true)) {
                                    Text("Home             Explore             Search             Library",color=Color.White)
                                }
                                Box(Modifier.align(Alignment.BottomCenter).padding(bottom=88.dp)) {
                                    OdifyMiniPlayer(OdifySong("benchmark","입춘","한로로","",""),true,false,backdrop,Color.Cyan,
                                        compact.value,{compact.value=it},{},{},{},{},{})
                                }
                            }
                        }
                    }
                }
            }
            Thread.sleep(2500)
            scenario.onActivity {activity ->
                @Suppress("DEPRECATION") val display=activity.windowManager.defaultDisplay
                refresh=display.refreshRate
            }
            phase.set("idle");Thread.sleep(1800)
            phase.set("transition")
            repeat(12) {scenario.onActivity {compact.value=!compact.value};Thread.sleep(1000)}
            phase.set("settled");Thread.sleep(1800)
            val rows=synchronized(timings){timings.toList()}
            fun summary(name:String):JSONObject {
                val data=rows.filter {it.first==name}.map {it.second/1_000_000.0}.sorted()
                fun p(f:Double)=if(data.isEmpty())0.0 else data[((data.size-1)*f).toInt()]
                return JSONObject().put("frames",data.size).put("p50_ms",p(.5)).put("p90_ms",p(.9))
                    .put("p95_ms",p(.95)).put("over_8_33ms",data.count {it>8.333}).put("over_16_67ms",data.count {it>16.667})
            }
            val report=JSONObject().put("refresh_hz",refresh).put("idle",summary("idle"))
                .put("transition",summary("transition")).put("settled",summary("settled"))
                .put("samples",JSONArray().apply {rows.forEach {put(JSONArray().put(it.first).put(it.second).put(it.third))}})
            File(test.targetContext.filesDir,"glass_frame_test.json").writeText(report.toString(2))
            assertTrue("Expected rendered transition frames",rows.count {it.first=="transition"}>100)
        } finally {
            scenario.onActivity {activity ->window?.removeOnFrameMetricsAvailableListener(listener)
                prior?.let {activity.window.attributes=it};activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            scenario.close();thread.quitSafely()
        }
    }
}
