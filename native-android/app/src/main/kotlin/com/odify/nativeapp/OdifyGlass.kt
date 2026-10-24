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
fun Modifier.odifyGlass(backdrop: OdifyBackdrop, shape: Shape,glow:Color=Color.Transparent): Modifier = composed {
    var coordinates by remember {mutableStateOf<LayoutCoordinates?>(null)}
    val crop=rememberGraphicsLayer()
    val radius=with(LocalDensity.current) {24.dp.toPx()}
    val bleed=ceil(radius*2).toInt()
    val effect=remember(radius) {BlurEffect(radius,radius,TileMode.Clamp)}
    clip(shape).onGloballyPositioned {coordinates=it}.drawWithContent {
        @Suppress("UNUSED_VARIABLE") val frame=backdrop.frame
        val target=coordinates?.takeIf {it.isAttached}
        val source=backdrop.coordinates?.takeIf {it.isAttached}
        if(Build.VERSION.SDK_INT>=31 && target!=null && source!=null) {
            // Convert the live coordinates, including ancestor translations.
            // Clipped boundsInRoot() loses the offscreen part during a spring.
            val offset=source.localPositionOf(target,Offset.Zero)
            crop.renderEffect=effect
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
        drawRect(Color.Black.copy(alpha=.18f))
        drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha=.17f),
            Color.White.copy(alpha=.025f),Color.White.copy(alpha=.08f)),
            start=Offset.Zero,end=Offset(size.width,size.height)))
        if(glow.alpha>0f) drawRect(Brush.radialGradient(listOf(glow.copy(alpha=.28f),
            glow.copy(alpha=.09f),Color.Transparent),
            center=Offset(size.width*.22f,size.height*.8f),radius=size.width*.85f))
        drawContent()
    }.border(.7.dp,Brush.verticalGradient(listOf(Color.White.copy(alpha=.34f),
        Color.White.copy(alpha=.08f),Color.White.copy(alpha=.19f))),shape)
}
