package com.odify.nativeapp

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Two capsules briefly share a narrowing fluid neck while opening. */
internal class OdifyDropletShape(private val split:Float):Shape {
    override fun createOutline(size:Size,layoutDirection:LayoutDirection,density:Density):Outline {
        val p=split.coerceIn(0f,1f)
        val right=(with(density){120.dp.toPx()}*p).coerceAtMost(size.width*.44f)
        val gap=with(density){8.dp.toPx()}*p
        if(p<.02f || right<1f) return Outline.Rounded(RoundRect(Rect(0f,0f,size.width,size.height),CornerRadius(size.height/2)))
        val left=size.width-right-gap
        val first=Path().apply {addRoundRect(RoundRect(Rect(0f,0f,left,size.height),CornerRadius(size.height/2)))}
        val second=Path().apply {addRoundRect(RoundRect(Rect(left+gap,0f,size.width,size.height),CornerRadius(minOf(right,size.height)/2)))}
        var path=Path.combine(PathOperation.Union,first,second)
        val bridge=Path().apply {
            if(p<.97f) {
                val neck=size.height*.35f*(1f-p)/.97f
                val overlap=minOf(size.height*.25f,right*.3f)
                val mid=left+gap/2
                moveTo(left-overlap,size.height/2-neck)
                cubicTo(mid,size.height/2-neck*.55f,mid,size.height/2-neck*.55f,left+gap+overlap,size.height/2-neck)
                lineTo(left+gap+overlap,size.height/2+neck)
                cubicTo(mid,size.height/2+neck*.55f,mid,size.height/2+neck*.55f,left-overlap,size.height/2+neck)
                close()
            }
        }
        if(p<.97f) path=Path.combine(PathOperation.Union,path,bridge)
        return Outline.Generic(path)
    }
}
