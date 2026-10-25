package com.odify.nativeapp

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
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
        val ox=max(qx,0f);val oy=max(qy,0f)
        return sqrt(ox*ox+oy*oy)+min(max(qx,qy),0f)-radius
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
        val path=Path()
        // At both settled ends the exact field is an ordinary capsule pair.
        // Solving thousands of distances for every idle corner pulse was the
        // dominant avoidable CPU cost; native cubic round rects are exact here.
        if(progress<=0f) {
            path.addRoundRect(RoundRect(Rect(0f,0f,w,h),CornerRadius(cy)))
        } else if(progress>=.94f) {
            val right=rightWidthPx.coerceIn(cy,w*.6f);val left=w-right-gapPx
            path.addRoundRect(RoundRect(Rect(0f,0f,left,h),CornerRadius(radius)))
            path.addRoundRect(RoundRect(Rect(left+gapPx,0f,w,h),CornerRadius(cy)))
        } else {
            // Only the transient neck needs a sampled field. Cache invariants
            // once, avoid boxed point pairs, and stop at subpixel precision.
            val field=OdifySurfaceEvaluator(w,h,progress,rightWidthPx,gapPx,radius)
            val count=ceil(w/max(1.5f*density.density,1f)).toInt().coerceIn(64,512)
            val xs=FloatArray(count+1);val ys=FloatArray(count+1);var points=0
            fun closeLobe() {
                if(points==0) return
                path.moveTo(xs[0],cy)
                for(j in 0 until points) path.lineTo(xs[j],ys[j])
                path.lineTo(xs[points-1],cy)
                for(j in points-1 downTo 0) path.lineTo(xs[j],h-ys[j])
                path.close();points=0
            }
            for(i in 0..count) {
                val x=w*i/count
                if(field.distance(x,cy)>.001f) {closeLobe();continue}
                var low=0f;var high=cy
                if(field.distance(x,0f)<=0f) high=0f else repeat(9) {
                    val middle=(low+high)/2
                    if(field.distance(x,middle)>0f) low=middle else high=middle
                }
                xs[points]=x;ys[points]=high;points++
            }
            closeLobe()
        }
        return Outline.Generic(path).also {cachedSize=size;cached=it}
    }
}

/** Per-outline constants, not per-point allocations/repeated stage calculations. */
private class OdifySurfaceEvaluator(val w:Float,h:Float,p:Float,rightWidth:Float,gap:Float,val radius:Float) {
    val cy=h/2;val right=rightWidth.coerceIn(cy,w*.6f);val left=w-right-gap
    val k=h*.72f*(1f-fluidStage(p,.15f,.94f));val inverseK=1f/max(k,.001f)
    val t=fluidStage(p,0f,.94f)
    fun distance(x:Float,y:Float):Float {
        val whole=OdifyLiquidSurface.capsule(x,y,w/2,cy,w/2,cy,cy)
        val a=OdifyLiquidSurface.capsule(x,y,left/2,cy,left/2,cy,radius)
        val b=OdifyLiquidSurface.capsule(x,y,w-right/2,cy,right/2,cy,cy)
        val blend=(.5f+.5f*(b-a)*inverseK).coerceIn(0f,1f)
        val merged=b+(a-b)*blend-k*blend*(1f-blend)
        return max(whole,whole+(merged-whole)*t)
    }
}
