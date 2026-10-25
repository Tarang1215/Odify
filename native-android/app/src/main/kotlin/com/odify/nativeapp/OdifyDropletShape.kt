package com.odify.nativeapp

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos

internal fun fluidStage(value:Float,start:Float,end:Float):Float {
    val t=((value-start)/(end-start)).coerceIn(0f,1f)
    return t*t*(3f-2f*t)
}

/** The neck reaches zero before the lobes move apart. Content and glass
 * share lobe boundaries, and no visible contour is abruptly removed. */
internal class OdifyDropletShape(
    private val progress:Float, private val rightWidthPx:Float,
    private val gapPx:Float, private val phase:Float=0f, private val motion:Float=0f
):Shape {
    override fun createOutline(size:Size,layoutDirection:LayoutDirection,density:Density):Outline {
        val w=size.width;val h=size.height;val cy=h/2f;val r=cy
        val right=rightWidthPx.coerceIn(r,w*.6f)
        val left=w-right-gapPx
        val neck=r*(1f-fluidStage(progress,.20f,.84f))
        // Small asymmetric edge motion; labels and controls remain still.
        val breath=sin(phase*2f*PI).toFloat()*motion
        val curl=cos(phase*2f*PI).toFloat()*motion
        val outerTop=r+breath*1.6f;val outerBottom=r-breath*1.6f
        val innerTop=r+curl*1.4f;val innerBottom=r-curl*1.4f
        val top=motion+breath*.65f;val bottom=h-motion+breath*.45f
        val ry=(bottom-top)/2f;val center=(top+bottom)/2f
        val k=.55228475f
        val path=Path()
        fun leftOuterBottom() {
            path.lineTo(outerBottom,bottom)
            path.cubicTo(outerBottom*(1f-k),bottom,0f,center+ry*k,0f,center)
            path.cubicTo(0f,center-ry*k,outerTop*(1f-k),top,outerTop,top)
        }
        if(progress>=.84f) {
            // At the touching point this is the limit of the connected path.
            path.moveTo(outerTop,top);path.lineTo(left-innerTop,top)
            path.cubicTo(left-innerTop*(1f-k),top,left,center-ry*k,left,center)
            path.cubicTo(left,center+ry*k,left-innerBottom*(1f-k),bottom,left-innerBottom,bottom)
            leftOuterBottom();path.close()
            val start=left+gapPx
            path.moveTo(start+r,0f);path.lineTo(w-r,0f)
            path.cubicTo(w-r+r*k,0f,w,cy-r*k,w,cy)
            path.cubicTo(w,cy+r*k,w-r+r*k,h,w-r,h)
            path.lineTo(start+r,h)
            path.cubicTo(start+r-r*k,h,start,cy+r*k,start,cy)
            path.cubicTo(start,cy-r*k,start+r-r*k,0f,start+r,0f);path.close()
        } else {
            val mid=left+gapPx/2f
            val roundness=(1f-neck/r).coerceIn(0f,1f)
            val innerLeft=left-r;val innerRight=left+gapPx+r
            path.moveTo(outerTop,top);path.lineTo(innerLeft,top)
            path.cubicTo(innerLeft+r*k,top,mid-neck*.6f,cy-neck-r*k*roundness,mid,cy-neck)
            path.cubicTo(mid+neck*.6f,cy-neck-r*k*roundness,innerRight-r*k,0f,innerRight,0f)
            path.lineTo(w-r,0f)
            path.cubicTo(w-r+r*k,0f,w,cy-r*k,w,cy)
            path.cubicTo(w,cy+r*k,w-r+r*k,h,w-r,h)
            path.lineTo(innerRight,h)
            path.cubicTo(innerRight-r*k,h,mid+neck*.6f,cy+neck+r*k*roundness,mid,cy+neck)
            path.cubicTo(mid-neck*.6f,cy+neck+r*k*roundness,innerLeft+r*k,bottom,innerLeft,bottom)
            leftOuterBottom();path.close()
        }
        return Outline.Generic(path)
    }
}
