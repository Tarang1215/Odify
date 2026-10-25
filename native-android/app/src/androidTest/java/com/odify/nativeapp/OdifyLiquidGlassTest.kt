package com.odify.nativeapp

import android.graphics.RuntimeShader
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OdifyLiquidGlassTest {
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
