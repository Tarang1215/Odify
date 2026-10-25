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
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.ceil

@Stable
class OdifyBackdrop(val layer: GraphicsLayer) {
    var coordinates: LayoutCoordinates? = null
    // Read only in the glass draw pass. Recomposition/layout are not needed
    // when scrolling children or ancestor render layers move independently.
    var frame by mutableLongStateOf(0L)
    private var sequence=0L
    private var refreshUntil=0L
    private var lastOrigin=Offset.Unspecified
    private var lastSize=IntSize.Zero
    // Keep the source's draw pass out of the observed frame state. Reading and
    // writing that same state there would create a self-invalidating draw loop.
    fun changed(){frame=++sequence}
    fun moving(){refreshUntil=android.os.SystemClock.uptimeMillis()+1600L}
    fun needsMotionFrame()=android.os.SystemClock.uptimeMillis()<refreshUntil
    fun placed(value:LayoutCoordinates) {
        val origin=value.positionInRoot()
        if(origin!=lastOrigin || value.size!=lastSize) {moving();lastOrigin=origin;lastSize=value.size}
        coordinates=value
    }
    val scrolling=object:NestedScrollConnection {
        override fun onPostScroll(consumed:Offset,available:Offset,source:NestedScrollSource):Offset {
            if(consumed!=Offset.Zero || available!=Offset.Zero) moving()
            return Offset.Zero
        }
    }
}

@Composable fun rememberOdifyBackdrop(): OdifyBackdrop {
    val layer=rememberGraphicsLayer()
    val backdrop=remember(layer) {OdifyBackdrop(layer)}
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var resumed by remember {mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))}
    DisposableEffect(lifecycle) {
        val observer=androidx.lifecycle.LifecycleEventObserver {_,_->resumed=lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)}
        lifecycle.addObserver(observer);onDispose {lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(backdrop,resumed) {
        if(resumed) while(isActive) {
            if(backdrop.needsMotionFrame()) withFrameNanos {backdrop.changed()} else delay(32)
        }
    }
    return backdrop
}

/** Records only the page, excluding the overlays, so there is no feedback loop. */
fun Modifier.recordOdifyBackdrop(backdrop: OdifyBackdrop): Modifier =
    nestedScroll(backdrop.scrolling).pointerInput(backdrop) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
            do {
                backdrop.moving()
                val event=awaitPointerEvent(PointerEventPass.Initial)
            } while(event.changes.any {it.pressed})
        }
    }.onGloballyPositioned {backdrop.placed(it)}.drawWithContent {
        backdrop.layer.record {this@drawWithContent.drawContent()}
        backdrop.changed()
        drawLayer(backdrop.layer)
    }

/** Replays the real page region through a bounded blur layer; foreground stays sharp. */
fun Modifier.odifyGlass(backdrop: OdifyBackdrop, shape: Shape,glow:Color=Color.Transparent,split:Float=0f,
    rightWidth:Dp=120.dp,gap:Dp=8.dp,navigation:Boolean=false,transparencyOverride:Float?=null): Modifier = composed {
    val preferences=LocalOdifyGlassPreferences.current
    val transparency=transparencyOverride ?: (if(navigation)preferences?.navigation else preferences?.player) ?: .72f
    val shade=(.035f+1.08f*(1f-transparency)*(1f-transparency)).coerceAtMost(.88f)
    var coordinates by remember {mutableStateOf<LayoutCoordinates?>(null)}
    val crop=rememberGraphicsLayer()
    val sharpCrop=rememberGraphicsLayer()
    val radius=with(LocalDensity.current) {((if(navigation)4f+14f*(1f-transparency) else 3f+12f*(1f-transparency)).dp).toPx()}
    val bleed=ceil(radius*2).toInt()
    val effect=remember(radius) {BlurEffect(radius,radius,TileMode.Clamp)}
    val lens=remember {if(Build.VERSION.SDK_INT>=33) runCatching {android.graphics.RuntimeShader(OdifyGlassLens.source)}.getOrNull() else null}
    val sharpLens=remember {if(Build.VERSION.SDK_INT>=33) runCatching {android.graphics.RuntimeShader(OdifyGlassLens.source)}.getOrNull() else null}
    val lensCache=remember {OdifyLensCache()}
    graphicsLayer {
        this.shape=(shape as? OdifyDropletShape)?.frameShape() ?: shape
        clip=true
    }.onGloballyPositioned {coordinates=it}.drawWithContent {
        val frame=backdrop.frame
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
                val leftCorner=if(split<=0f) corner else (shape as? OdifyDropletShape)?.leftRadius(size.height) ?: corner
                if(lensCache.size!=size || lensCache.corner!=corner || lensCache.split!=split || lensCache.right!=rightPx || lensCache.gap!=gapPx || lensCache.leftCorner!=leftCorner || lensCache.radius!=radius) {
                fun configure(shader:android.graphics.RuntimeShader,edgePass:Float) {
                    shader.setFloatUniform("origin",bleed.toFloat(),bleed.toFloat())
                    shader.setFloatUniform("bounds",size.width,size.height)
                    shader.setFloatUniform("corner",corner.coerceAtMost(size.height/2))
                    shader.setFloatUniform("depth",12.dp.toPx())
                    shader.setFloatUniform("leftCorner",leftCorner)
                    shader.setFloatUniform("strength",10.dp.toPx())
                    shader.setFloatUniform("split",split.coerceIn(0f,1f))
                    shader.setFloatUniform("rightWidth",rightPx)
                    shader.setFloatUniform("gap",gapPx)
                    shader.setFloatUniform("edgePass",edgePass)
                }
                configure(lens,0f)
                lensCache.effect=android.graphics.RenderEffect.createChainEffect(
                    android.graphics.RenderEffect.createRuntimeShaderEffect(lens,"content"),
                    android.graphics.RenderEffect.createBlurEffect(radius,radius,android.graphics.Shader.TileMode.CLAMP)).asComposeRenderEffect()
                if(sharpLens!=null) {
                    configure(sharpLens,1f)
                    lensCache.sharpEffect=android.graphics.RenderEffect.createRuntimeShaderEffect(sharpLens,"content").asComposeRenderEffect()
                }
                lensCache.size=size;lensCache.corner=corner;lensCache.split=split;lensCache.right=rightPx
                lensCache.gap=gapPx;lensCache.leftCorner=leftCorner;lensCache.radius=radius
                }
                crop.renderEffect=lensCache.effect
            } else crop.renderEffect=effect
            val captureSize=IntSize(ceil(size.width).toInt()+bleed*2,ceil(size.height).toInt()+bleed*2)
            val refreshCapture=lensCache.captureFrame!=frame || lensCache.captureOffset!=offset || lensCache.captureSize!=captureSize
            if(refreshCapture) crop.record(size=captureSize) {
                // The snapshot must be opaque. Blurring transparent glyphs
                // over the original page leaves the original text sharp.
                drawRect(OdifyPalette.background)
                clipRect {
                    translate(bleed-offset.x,bleed-offset.y) {drawLayer(backdrop.layer)}
                }
            }
            translate(-bleed.toFloat(),-bleed.toFloat()) {drawLayer(crop)}
            // A second GPU layer samples the *unblurred* live page at the rim.
            // Refracting only the blurred input erases the details that reveal glass.
            if(Build.VERSION.SDK_INT>=33 && lensCache.sharpEffect!=null) {
                sharpCrop.renderEffect=lensCache.sharpEffect
                if(refreshCapture) sharpCrop.record(size=captureSize) {
                    drawRect(OdifyPalette.background)
                    clipRect {translate(bleed-offset.x,bleed-offset.y) {drawLayer(backdrop.layer)}}
                }
                translate(-bleed.toFloat(),-bleed.toFloat()) {drawLayer(sharpCrop)}
            }
            lensCache.captureFrame=frame;lensCache.captureOffset=offset;lensCache.captureSize=captureSize
        } else drawRect(Color(0xE82B2E35))
        // A mild neutral tint preserves the blurred artwork's actual colors.
        drawRect(Color.Black.copy(alpha=shade))
        drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha=.045f+(.72f-transparency)*.04f),
            Color.White.copy(alpha=.015f),Color.White.copy(alpha=.025f)),
            start=Offset.Zero,end=Offset(size.width,size.height)))
        if(glow.alpha>0f) drawRect(Brush.radialGradient(listOf(glow.copy(alpha=.13f),
            glow.copy(alpha=.035f),Color.Transparent),
            center=Offset(size.width*.22f,size.height*.8f),radius=size.width*.85f))
        drawContent()
    }.border(.45.dp,Brush.verticalGradient(listOf(Color.White.copy(alpha=.22f),
        Color.White.copy(alpha=.035f),Color.White.copy(alpha=.14f))),shape)
}

/** AGSL adaptation of Liquid Glass Studio's smooth SDF union, Snell refraction,
 * dispersion and directional Fresnel/glare. MIT attribution is bundled in licenses/. */
internal object OdifyGlassLens {
    const val source="""
        uniform shader content;
        uniform float2 origin;
        uniform float2 bounds;
        uniform float corner;
        uniform float leftCorner;
        uniform float depth;
        uniform float strength;
        uniform float split;
        uniform float rightWidth;
        uniform float gap;
        uniform float edgePass;
        float capsule(float2 p,float2 center,float2 halfSize,float r) {
            float2 q=abs(p-center)-halfSize+r;
            return length(max(q,0.0))+min(max(q.x,q.y),0.0)-r;
        }
        float2 wholeNormal(float2 p) {
            float2 delta=p-bounds*0.5;
            float2 q=abs(delta)-bounds*0.5+corner;
            float2 outside=max(q,0.0);
            float2 n=outside/max(length(outside),0.001);
            if(max(q.x,q.y)<=0.0) n=q.x>q.y?float2(1.0,0.0):float2(0.0,1.0);
            return n*sign(delta);
        }
        float stage(float p,float a,float b) {return smoothstep(a,b,p);}
        float distanceToSurface(float2 p) {
            float whole=capsule(p,bounds*0.5,bounds*0.5,corner);
            if(split<=0.0) return whole;
            float r=bounds.y*0.5;
            float right=clamp(rightWidth,r,bounds.x*0.6);
            float left=bounds.x-right-gap;
            float a=capsule(p,float2(left*0.5,r),float2(left*0.5,r),leftCorner);
            float b=capsule(p,float2(bounds.x-right*0.5,r),float2(right*0.5,r),r);
            float k=bounds.y*0.72*(1.0-stage(split,0.15,0.94));
            float blend=clamp(0.5+0.5*(b-a)/max(k,0.001),0.0,1.0);
            float merged=mix(b,a,blend)-k*blend*(1.0-blend);
            return max(whole,mix(whole,merged,stage(split,0.0,0.94)));
        }
        half4 main(float2 coord) {
            float2 p=coord-origin;
            float d=distanceToSurface(p);
            // Effects render the rectangular crop (including its blur bleed).
            // Pixels outside the actual surface never contribute to its clip.
            if(d>0.0) return half4(0.0);
            float inward=max(-d,0.0);
            // The sharp pass is only a rim. Skip normals and texture samples
            // over its transparent center; retain the real unblurred edge.
            if(edgePass>0.5 && inward>=depth) return half4(0.0);
            if(edgePass<0.5 && inward>=depth*1.8) return content.eval(coord);
            float rim=clamp(1.0-inward/max(depth,1.0),0.0,1.0);
            // Joined capsules (including nav) have an analytic normal. Only
            // the changing liquid neck needs four more distance evaluations.
            float2 n=split<=0.0?wholeNormal(p):float2(
                distanceToSurface(p+float2(0.5,0.0))-distanceToSurface(p-float2(0.5,0.0)),
                distanceToSurface(p+float2(0.0,0.5))-distanceToSurface(p-float2(0.0,0.5)));
            n=n/max(length(n),0.001);
            // Surface slope bends the sampled page instead of painting a tint.
            float thetaI=asin(clamp(rim*rim,0.0,0.999));
            float thetaT=asin(sin(thetaI)/1.45);
            float edge=clamp(tan(thetaI-thetaT),0.0,1.3);
            float2 bend=-n*strength*edge;
            half4 c=content.eval(coord+bend);
            c.r=content.eval(coord+bend*0.98).r;
            c.b=content.eval(coord+bend*1.02).b;
            if(edgePass>0.5) {
                float opacity=pow(rim,1.4)*0.78;
                return half4(c.rgb*half(opacity),half(opacity));
            }
            float fresnel=pow(clamp(1.0-inward/(depth*1.8),0.0,1.0),5.0);
            float facing=dot(n,normalize(float2(-0.65,-0.76)));
            float glare=pow(max(facing,0.0),3.0)*0.27+pow(max(-facing,0.0),4.0)*0.09;
            half3 reflected=content.eval(coord+n*strength*rim*0.65).rgb;
            c.rgb=mix(c.rgb,reflected,half(rim*rim*0.14));
            c.rgb=mix(c.rgb,half3(0.94,0.97,1.0),half(fresnel*0.12+pow(rim,2.0)*glare));
            return c;
        }
    """
}

private class OdifyLensCache {
    var captureFrame=-1L
    var captureOffset=Offset.Unspecified
    var captureSize=IntSize.Zero
    var size=androidx.compose.ui.geometry.Size.Zero
    var corner=Float.NaN
    var leftCorner=Float.NaN
    var split=Float.NaN
    var right=Float.NaN
    var gap=Float.NaN
    var radius=Float.NaN
    var effect:RenderEffect?=null
    var sharpEffect:RenderEffect?=null
}
