package com.odify.nativeapp

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.ceil

class OdifyBackdrop(val layer: GraphicsLayer) {
    var coordinates: LayoutCoordinates? = null
    // Read only in the glass draw pass. Recomposition/layout are not needed
    // when scrolling children or ancestor render layers move independently.
    var frame by mutableLongStateOf(0L)
}

@Composable fun rememberOdifyBackdrop(): OdifyBackdrop {
    val layer=rememberGraphicsLayer()
    val backdrop=remember(layer) {OdifyBackdrop(layer)}
    LaunchedEffect(backdrop) {
        while(isActive) withFrameNanos {backdrop.frame=it}
    }
    return backdrop
}

/** Records only the page, excluding the overlays, so there is no feedback loop. */
fun Modifier.recordOdifyBackdrop(backdrop: OdifyBackdrop): Modifier =
    onGloballyPositioned {backdrop.coordinates=it}.drawWithContent {
        backdrop.layer.record {this@drawWithContent.drawContent()}
        drawLayer(backdrop.layer)
    }

/** Replays the real page region through a bounded blur layer; foreground stays sharp. */
fun Modifier.odifyGlass(backdrop: OdifyBackdrop, shape: Shape,glow:Color=Color.Transparent,split:Float=0f,
    rightWidth:Dp=120.dp,gap:Dp=8.dp): Modifier = composed {
    var coordinates by remember {mutableStateOf<LayoutCoordinates?>(null)}
    val crop=rememberGraphicsLayer()
    val radius=with(LocalDensity.current) {24.dp.toPx()}
    val bleed=ceil(radius*2).toInt()
    val effect=remember(radius) {BlurEffect(radius,radius,TileMode.Clamp)}
    val lens=remember {if(Build.VERSION.SDK_INT>=33) runCatching {android.graphics.RuntimeShader(OdifyGlassLens.source)}.getOrNull() else null}
    val lensCache=remember {OdifyLensCache()}
    clip(shape).onGloballyPositioned {coordinates=it}.drawWithContent {
        @Suppress("UNUSED_VARIABLE") val frame=backdrop.frame
        val target=coordinates?.takeIf {it.isAttached}
        val source=backdrop.coordinates?.takeIf {it.isAttached}
        if(Build.VERSION.SDK_INT>=31 && target!=null && source!=null) {
            // Convert the live coordinates, including ancestor translations.
            // Clipped boundsInRoot() loses the offscreen part during a spring.
            val offset=source.localPositionOf(target,Offset.Zero)
            if(Build.VERSION.SDK_INT>=33 && lens!=null) {
                val outline=shape.createOutline(size,layoutDirection,this)
                val corner=(outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: size.height/2
                val rightPx=rightWidth.toPx();val gapPx=gap.toPx()
                if(lensCache.size!=size || lensCache.corner!=corner || lensCache.split!=split || lensCache.right!=rightPx || lensCache.gap!=gapPx) {
                lens.setFloatUniform("origin",bleed.toFloat(),bleed.toFloat())
                lens.setFloatUniform("bounds",size.width,size.height)
                lens.setFloatUniform("corner",corner.coerceAtMost(size.height/2))
                lens.setFloatUniform("depth",10.dp.toPx())
                lens.setFloatUniform("strength",7.dp.toPx())
                lens.setFloatUniform("split",split.coerceIn(0f,1f))
                lens.setFloatUniform("rightWidth",rightPx)
                lens.setFloatUniform("gap",gapPx)
                lensCache.effect=android.graphics.RenderEffect.createChainEffect(
                    android.graphics.RenderEffect.createRuntimeShaderEffect(lens,"content"),
                    android.graphics.RenderEffect.createBlurEffect(radius,radius,android.graphics.Shader.TileMode.CLAMP)).asComposeRenderEffect()
                lensCache.size=size;lensCache.corner=corner;lensCache.split=split;lensCache.right=rightPx;lensCache.gap=gapPx
                }
                crop.renderEffect=lensCache.effect
            } else crop.renderEffect=effect
            crop.record(size=IntSize(ceil(size.width).toInt()+bleed*2,
                ceil(size.height).toInt()+bleed*2)) {
                // The snapshot must be opaque. Blurring transparent glyphs
                // over the original page leaves the original text sharp.
                drawRect(OdifyPalette.background)
                clipRect {
                    translate(bleed-offset.x,bleed-offset.y) {drawLayer(backdrop.layer)}
                }
            }
            translate(-bleed.toFloat(),-bleed.toFloat()) {drawLayer(crop)}
        } else drawRect(Color(0xE82B2E35))
        // A mild neutral tint preserves the blurred artwork's actual colors.
        drawRect(Color.Black.copy(alpha=.12f))
        drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha=.14f),
            Color.White.copy(alpha=.015f),Color.White.copy(alpha=.07f)),
            start=Offset.Zero,end=Offset(size.width,size.height)))
        if(glow.alpha>0f) drawRect(Brush.radialGradient(listOf(glow.copy(alpha=.28f),
            glow.copy(alpha=.09f),Color.Transparent),
            center=Offset(size.width*.22f,size.height*.8f),radius=size.width*.85f))
        drawContent()
    }.border(.7.dp,Brush.verticalGradient(listOf(Color.White.copy(alpha=.34f),
        Color.White.copy(alpha=.08f),Color.White.copy(alpha=.19f))),shape)
}

/** Original AGSL lens: the sampled page bends at the surface edge, text stays sharp. */
internal object OdifyGlassLens {
    const val source="""
        uniform shader content;
        uniform float2 origin;
        uniform float2 bounds;
        uniform float corner;
        uniform float depth;
        uniform float strength;
        uniform float split;
        uniform float rightWidth;
        uniform float gap;
        float capsule(float2 p,float2 center,float2 halfSize,float r) {
            float2 q=abs(p-center)-halfSize+r;
            return length(max(q,0.0))+min(max(q.x,q.y),0.0)-r;
        }
        float distanceToSurface(float2 p) {
            if(split<0.02) return capsule(p,bounds*0.5,bounds*0.5,corner);
            float right=min(rightWidth,bounds.x*0.6);
            float left=bounds.x-right-gap;
            float a=capsule(p,float2(left*0.5,bounds.y*0.5),float2(left*0.5,bounds.y*0.5),bounds.y*0.5);
            float b=capsule(p,float2(bounds.x-right*0.5,bounds.y*0.5),float2(right*0.5,bounds.y*0.5),min(right,bounds.y)*0.5);
            float k=bounds.y*0.34*(1.0-smoothstep(0.2,0.84,split));
            float blend=max(k-abs(a-b),0.0)/max(k,0.001);
            return min(a,b)-blend*blend*k*0.25;
        }
        half4 main(float2 coord) {
            float2 p=coord-origin;
            float d=distanceToSurface(p);
            float rim=pow(clamp(1.0+d/max(depth,1.0),0.0,1.0),2.0);
            float2 normal=float2(distanceToSurface(p+float2(0.5,0.0))-distanceToSurface(p-float2(0.5,0.0)),
                distanceToSurface(p+float2(0.0,0.5))-distanceToSurface(p-float2(0.0,0.5)));
            normal=normal/max(length(normal),0.001);
            float2 bend=normal*strength*rim;
            half4 c=content.eval(coord+bend);
            c.r=content.eval(coord+bend*1.035).r;
            c.b=content.eval(coord+bend*0.965).b;
            float shine=pow(max(dot(normal,normalize(float2(-0.6,-0.8))),0.0),3.0)*rim;
            c.rgb+=half3(shine*0.11);
            return c;
        }
    """
}

private class OdifyLensCache {
    var size=androidx.compose.ui.geometry.Size.Zero
    var corner=Float.NaN
    var split=Float.NaN
    var right=Float.NaN
    var gap=Float.NaN
    var effect:RenderEffect?=null
}
