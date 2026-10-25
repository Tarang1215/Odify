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
    @Test fun pulseChangesDrawOutlineWithoutReplacingTheComposedShape() {
        val phase=androidx.compose.runtime.mutableFloatStateOf(0f)
        val shape=OdifyDropletShape(1f,120f,12f,motion=2f,phaseProvider={phase.floatValue})
        fun draw():Bitmap {
            val outline=shape.createOutline(Size(360f,56f),LayoutDirection.Ltr,Density(1f)) as Outline.Generic
            return Bitmap.createBitmap(360,56,Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawPath(outline.path.asAndroidPath(),Paint().apply {color=android.graphics.Color.WHITE})
            }
        }
        val before=draw()
        phase.floatValue=.25f
        val after=draw()
        try {
            assertSame("All render layers share one contour per pulse",shape.frameShape(),shape.frameShape())
            assertEquals(26f,shape.frameShape().leftRadius(56f),.001f)
            assertFalse("The stable shape must invalidate its cached pulse contour",before.sameAs(after))
        } finally {before.recycle();after.recycle()}
    }
    @Test fun contourAndOpticalDistanceFieldAgreeThroughoutMerge() {
        for(gap in listOf(12f,80f)) for(progress in listOf(0f,.2f,.4f,.6f,.8f,1f)) {
            val outline=OdifyDropletShape(progress,120f,gap).createOutline(Size(360f,56f),LayoutDirection.Ltr,Density(1f)) as Outline.Generic
            val bitmap=Bitmap.createBitmap(360,56,Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawPath(outline.path.asAndroidPath(),Paint().apply {color=android.graphics.Color.WHITE})
            try {
                for(x in 2..357 step 3) for(y in 2..53 step 3) {
                    val distance=OdifyLiquidSurface.distance(x+.5f,y+.5f,360f,56f,progress,120f,gap)
                    if(kotlin.math.abs(distance)>2f) assertEquals("Contour/SDF mismatch p=$progress gap=$gap x=$x y=$y",
                        distance<0f,bitmap.getPixel(x,y)!=0)
                }
            } finally {bitmap.recycle()}
        }
    }
    @Test fun compactBorderHasNoRetracedInteriorSeam() {
        val outline=OdifyDropletShape(0f,43f,0f).createOutline(Size(320f,43f),LayoutDirection.Ltr,Density(1f)) as Outline.Generic
        val bitmap=Bitmap.createBitmap(320,43,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawPath(outline.path.asAndroidPath(),Paint().apply {
            color=android.graphics.Color.WHITE;style=Paint.Style.STROKE;strokeWidth=1f
        })
        try {for(y in 3..39) assertEquals("No internal stroke at old neck, y=$y",0,bitmap.getPixel(277,y))}
        finally {bitmap.recycle()}
    }
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
        shader.setFloatUniform("leftCorner",28f)
        shader.setFloatUniform("edgePass",0f)
        shader.setFloatUniform("strength",7f);shader.setFloatUniform("rightWidth",120f)
        shader.setFloatUniform("gap",8f)
        for(split in listOf(0f,.5f,1f)) {
            shader.setFloatUniform("split",split)
            for(pass in listOf(0f,1f)) {
                shader.setFloatUniform("edgePass",pass)
                assertNotNull(android.graphics.RenderEffect.createRuntimeShaderEffect(shader,"content"))
            }
        }
    }
}
