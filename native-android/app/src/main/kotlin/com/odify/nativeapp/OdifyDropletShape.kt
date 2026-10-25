package com.odify.nativeapp

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.*

internal fun fluidStage(value:Float,start:Float,end:Float):Float {
    val t=((value-start)/(end-start)).coerceIn(0f,1f)
    return t*t*(3f-2f*t)
}

/** Same signed distance field as the optical shader: silhouette, blur mask and
 * reflections pinch together. Polynomial smooth union adapted from Liquid Glass
 * Studio (Charles Yin, MIT); see licenses/Liquid-Glass-Studio-MIT.txt. */
internal object OdifyLiquidSurface {
    fun capsule(x:Float,y:Float,cx:Float,cy:Float,halfWidth:Float,halfHeight:Float,radius:Float):Float {
        val qx=abs(x-cx)-halfWidth+radius;val qy=abs(y-cy)-halfHeight+radius
        return hypot(max(qx,0f),max(qy,0f))+min(max(qx,qy),0f)-radius
    }
    fun distance(x:Float,y:Float,w:Float,h:Float,progress:Float,rightWidth:Float,gap:Float,leftRadius:Float=h/2):Float {
        val r=h/2;val whole=capsule(x,y,w/2,r,w/2,r,r)
        if(progress<=0f) return whole
        val right=rightWidth.coerceIn(r,w*.6f);val left=w-right-gap
        val a=capsule(x,y,left/2,r,left/2,r,leftRadius)
        val b=capsule(x,y,w-right/2,r,right/2,r,r)
        val k=h*.72f*(1f-fluidStage(progress,.15f,.94f))
        val blend=(.5f+.5f*(b-a)/max(k,.001f)).coerceIn(0f,1f)
        val merged=b+(a-b)*blend-k*blend*(1f-blend)
        val t=fluidStage(progress,0f,.94f)
        return max(whole,whole+(merged-whole)*t)
    }
}

internal class OdifyDropletShape(
    private val progress:Float,private val rightWidthPx:Float,
    private val gapPx:Float,private val phase:Float=0f,private val motion:Float=0f
):Shape {
    private var cachedSize=Size.Unspecified
    private var cached:Outline?=null
    fun leftRadius(height:Float)=(height/2-abs(sin(phase*2f*PI).toFloat())*motion).coerceAtLeast(height*.4f)
    override fun createOutline(size:Size,layoutDirection:LayoutDirection,density:Density):Outline {
        if(size==cachedSize) cached?.let {return it}
        val w=size.width;val h=size.height;val cy=h/2;val radius=leftRadius(h)
        val count=ceil(w/max(.75f*density.density,1f)).toInt().coerceAtLeast(64)
        val path=Path();val upper=ArrayList<Pair<Float,Float>>()
        fun closeLobe() {
            if(upper.isEmpty()) return
            path.moveTo(upper.first().first,cy)
            for((x,y) in upper) path.lineTo(x,y)
            path.lineTo(upper.last().first,cy)
            for((x,y) in upper.asReversed()) path.lineTo(x,h-y)
            path.close();upper.clear()
        }
        for(i in 0..count) {
            val x=w*i/count
            fun d(y:Float)=OdifyLiquidSurface.distance(x,y,w,h,progress,rightWidthPx,gapPx,radius)
            if(d(cy)>.001f) {closeLobe();continue}
            var low=0f;var high=cy
            if(d(0f)<0f) high=0f else repeat(12) {
                val middle=(low+high)/2
                if(d(middle)>0f) low=middle else high=middle
            }
            upper.add(x to high)
        }
        closeLobe()
        return Outline.Generic(path).also {cachedSize=size;cached=it}
    }
}
