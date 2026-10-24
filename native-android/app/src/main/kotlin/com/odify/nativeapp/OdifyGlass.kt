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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

class OdifyBackdrop(val layer: GraphicsLayer) {
    var coordinates: LayoutCoordinates? = null
}

@Composable fun rememberOdifyBackdrop(): OdifyBackdrop {
    val layer=rememberGraphicsLayer()
    return remember(layer) {OdifyBackdrop(layer)}
}

/** Records only the page, excluding the overlays, so there is no feedback loop. */
fun Modifier.recordOdifyBackdrop(backdrop: OdifyBackdrop): Modifier =
    onGloballyPositioned {backdrop.coordinates=it}.drawWithContent {
        backdrop.layer.record {this@drawWithContent.drawContent()}
        drawLayer(backdrop.layer)
    }

/** Replays the real page region through a bounded blur layer; foreground stays sharp. */
fun Modifier.odifyGlass(backdrop: OdifyBackdrop, shape: Shape): Modifier = composed {
    var coordinates by remember {mutableStateOf<LayoutCoordinates?>(null)}
    val crop=rememberGraphicsLayer()
    val radius=with(LocalDensity.current) {12.dp.toPx()}
    val effect=remember(radius) {BlurEffect(radius,radius,TileMode.Clamp)}
    clip(shape).onGloballyPositioned {coordinates=it}.drawWithContent {
        val target=coordinates?.takeIf {it.isAttached}?.boundsInRoot()
        val source=backdrop.coordinates?.takeIf {it.isAttached}?.boundsInRoot()
        if(Build.VERSION.SDK_INT>=31 && target!=null && source!=null) {
            val offset=target.topLeft-source.topLeft
            crop.renderEffect=effect
            crop.record {
                clipRect {
                    translate(-offset.x,-offset.y) {drawLayer(backdrop.layer)}
                }
            }
            drawLayer(crop)
        } else drawRect(Color(0xE82B2E35))
        // A mild neutral tint preserves the blurred artwork's actual colors.
        drawRect(Color.Black.copy(alpha=.22f))
        drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha=.17f),
            Color.White.copy(alpha=.025f),Color.White.copy(alpha=.08f)),
            start=Offset.Zero,end=Offset(size.width,size.height)))
        drawContent()
    }.border(.7.dp,Brush.verticalGradient(listOf(Color.White.copy(alpha=.34f),
        Color.White.copy(alpha=.08f),Color.White.copy(alpha=.19f))),shape)
}
