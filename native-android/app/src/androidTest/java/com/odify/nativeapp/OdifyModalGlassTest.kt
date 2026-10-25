package com.odify.nativeapp

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Exercises the app-owned fallback on devices without cross-window blur. */
class OdifyModalGlassTest {
    @Test fun capturesHardwareRenderedSourceAndOpensGlassInSeparateWindow() {
        val test=InstrumentationRegistry.getInstrumentation()
        val open=mutableStateOf(false)
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            lateinit var activity:MainActivity
            scenario.onActivity {
                activity=it
                it.setContent {MaterialTheme {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(Color.Red))
                        Box(Modifier.weight(1f).fillMaxHeight().background(Color.Blue))
                    }
                    if(open.value) OdifyGlassDialog(onDismiss={open.value=false},tint=Color.Green) {Text("Glass fixture")}
                }}
            }
            test.waitForIdleSync();Thread.sleep(300)
            val frame=runBlocking {withContext(Dispatchers.Main) {odifyModalSnapshot(activity.window)}}
            assertNotNull(frame)
            val bitmap=frame!!.image.asAndroidBitmap()
            assertTrue(bitmap.width<=720)
            val left=bitmap.getPixel(bitmap.width/4,bitmap.height/2)
            val right=bitmap.getPixel(bitmap.width*3/4,bitmap.height/2)
            assertTrue(android.graphics.Color.red(left)>android.graphics.Color.blue(left)+100)
            assertTrue(android.graphics.Color.blue(right)>android.graphics.Color.red(right)+100)
            test.runOnMainSync {open.value=true}
            test.waitForIdleSync();Thread.sleep(500)
            val root=test.uiAutomation.rootInActiveWindow
            assertEquals(contextPackage,root?.packageName?.toString())
            assertTrue(root?.findAccessibilityNodeInfosByText("Glass fixture")?.isNotEmpty()==true)
            test.runOnMainSync {open.value=false};test.waitForIdleSync()
        }
    }
    private val contextPackage get()=InstrumentationRegistry.getInstrumentation().targetContext.packageName
}
