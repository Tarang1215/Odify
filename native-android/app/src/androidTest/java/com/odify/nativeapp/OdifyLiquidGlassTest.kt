package com.odify.nativeapp

import android.graphics.RuntimeShader
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OdifyLiquidGlassTest {
    @Test fun separationDoesNotDropAVisibleBridgeInOneFrame() {
        fun mask(progress:Float):IntArray {
            val outline=OdifyDropletShape(progress,120f,10f*fluidStage(progress,.84f,1f))
                .createOutline(Size(320f,56f),LayoutDirection.Ltr,Density(1f)) as Outline.Generic
            val bitmap=Bitmap.createBitmap(320,56,Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawPath(outline.path.asAndroidPath(),Paint().apply{color=android.graphics.Color.WHITE})
            return IntArray(320*56).also {bitmap.getPixels(it,0,320,0,0,320,56);bitmap.recycle()}
        }
        val before=mask(.8399f);val after=mask(.8401f)
        assertTrue("Pinch must converge to the two touching rounded lobes",
            before.indices.count {before[it]!=after[it]}<20)
        val separated=mask(1f)
        assertEquals("Gap between lobes must be transparent",0,separated[28*320+195])
        assertNotEquals("Transport lobe remains present",0,separated[28*320+260])
    }
    @Test fun lensShaderCompilesForJoinedAndSplitSurfaces() {
        assumeTrue(Build.VERSION.SDK_INT>=33)
        val shader=RuntimeShader(OdifyGlassLens.source)
        shader.setFloatUniform("origin",48f,48f);shader.setFloatUniform("bounds",380f,56f)
        shader.setFloatUniform("corner",28f);shader.setFloatUniform("depth",10f)
        shader.setFloatUniform("strength",7f);shader.setFloatUniform("rightWidth",120f)
        shader.setFloatUniform("gap",8f)
        for(split in listOf(0f,.5f,1f)) {
            shader.setFloatUniform("split",split)
            assertNotNull(android.graphics.RenderEffect.createRuntimeShaderEffect(shader,"content"))
        }
    }
}
