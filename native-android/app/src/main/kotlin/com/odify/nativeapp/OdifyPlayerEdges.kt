package com.odify.nativeapp

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp

/** Feather only the closing panel's rim. Its artwork/text center stays sharp. */
fun Modifier.featherPlayerEdges(amount:()->Float):Modifier=drawWithCache {
    val paint=Paint()
    onDrawWithContent {
        val strength=amount().coerceIn(0f,1f)
        if(strength<.001f) drawContent()
        else {
            val band=36.dp.toPx()*strength
            val x=(band/size.width).coerceIn(.0001f,.18f)
            val y=(band/size.height).coerceIn(.0001f,.18f)
            drawIntoCanvas {it.saveLayer(Rect(0f,0f,size.width,size.height),paint)}
            drawContent()
            drawRect(Brush.horizontalGradient(0f to Color.Transparent,x to Color.White,
                1f-x to Color.White,1f to Color.Transparent),blendMode=BlendMode.DstIn)
            drawRect(Brush.verticalGradient(0f to Color.Transparent,y to Color.White,
                1f-y to Color.White,1f to Color.Transparent),blendMode=BlendMode.DstIn)
            drawIntoCanvas {it.restore()}
        }
    }
}
