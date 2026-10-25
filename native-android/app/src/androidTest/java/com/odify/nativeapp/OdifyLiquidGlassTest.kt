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
        fun mask(progress:Float,gap:Float=10f):IntArray {
            val outline=OdifyDropletShape(progress,120f,gap)
                .createOutline(Size(320f,56f),LayoutDirection.Ltr,Density(1f)) as Outline.Generic
            val bitmap=Bitmap.createBitmap(320,56,Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawPath(outline.path.asAndroidPath(),Paint().apply{color=android.graphics.Color.WHITE})
            return IntArray(320*56).also {bitmap.getPixels(it,0,320,0,0,320,56);bitmap.recycle()}
        }
        // Raster rounding at a near-zero gap can flip edge pixels. Reject a
        // disappearing area, rather than demanding identical aliased edges.
        fun neighbourFilled(mask:IntArray,index:Int):Boolean {
            val x=index%320;val y=index/320
            return (-1..1).any {dy -> (-1..1).any {dx ->
                val nx=x+dx;val ny=y+dy
                nx in 0..319 && ny in 0..55 && mask[ny*320+nx]!=0
            }}
        }
        for(gap in listOf(0f,10f,80f)) {
            val before=mask(.8399f,gap);val after=mask(.8401f,gap)
            val difference=before.indices.count {before[it]!=after[it]}
            val interiorLoss=before.indices.count {before[it]!=0 && after[it]==0 && !neighbourFilled(after,it)}
            val interiorGain=before.indices.count {before[it]==0 && after[it]!=0 && !neighbourFilled(before,it)}
            assertEquals("No interior may disappear (gap=$gap edge flips=$difference)",0,interiorLoss+interiorGain)
            assertTrue("Only thin-edge rounding may change",difference<before.size/100)
        }
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
