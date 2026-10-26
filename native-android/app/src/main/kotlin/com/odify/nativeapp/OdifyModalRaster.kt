package com.odify.nativeapp

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.IntSize
import kotlin.math.*

/** Immutable, bounded software glass. All filtering runs on Dispatchers.Default,
 * before motion, so window scaling never re-executes a full-screen GPU filter. */
internal object OdifyModalRaster {
    fun blur(source:Bitmap,radius:Int):Bitmap {
        val width=source.width;val height=source.height
        var input=IntArray(width*height);source.getPixels(input,0,width,0,0,width,height)
        var output=IntArray(input.size)
        val r=radius.coerceIn(1,40);val count=2*r+1
        repeat(3) {
            for(y in 0 until height) {
                val row=y*width
                var red=0;var green=0;var blue=0
                for(x in -r..r) {val c=input[row+x.coerceIn(0,width-1)];red+=c shr 16 and 255;green+=c shr 8 and 255;blue+=c and 255}
                for(x in 0 until width) {
                    output[row+x]=(255 shl 24) or ((red/count) shl 16) or ((green/count) shl 8) or (blue/count)
                    val left=input[row+(x-r).coerceAtLeast(0)];val right=input[row+(x+r+1).coerceAtMost(width-1)]
                    red+=(right shr 16 and 255)-(left shr 16 and 255);green+=(right shr 8 and 255)-(left shr 8 and 255);blue+=(right and 255)-(left and 255)
                }
            }
            val swap=input;input=output;output=swap
            for(x in 0 until width) {
                var red=0;var green=0;var blue=0
                for(y in -r..r) {val c=input[y.coerceIn(0,height-1)*width+x];red+=c shr 16 and 255;green+=c shr 8 and 255;blue+=c and 255}
                for(y in 0 until height) {
                    output[y*width+x]=(255 shl 24) or ((red/count) shl 16) or ((green/count) shl 8) or (blue/count)
                    val top=input[(y-r).coerceAtLeast(0)*width+x];val bottom=input[(y+r+1).coerceAtMost(height-1)*width+x]
                    red+=(bottom shr 16 and 255)-(top shr 16 and 255);green+=(bottom shr 8 and 255)-(top shr 8 and 255);blue+=(bottom and 255)-(top and 255)
                }
            }
            val next=input;input=output;output=next
        }
        return Bitmap.createBitmap(input,width,height,Bitmap.Config.ARGB_8888)
    }

    fun surface(frame:OdifyModalSnapshot,origin:Offset,size:IntSize,cornerPx:Float,density:Float,transparency:Float):Bitmap {
        val width=size.width.coerceAtMost(720).coerceAtLeast(1)
        val scale=width.toFloat()/size.width.coerceAtLeast(1)
        val height=(size.height*scale).roundToInt().coerceAtLeast(1)
        val source=frame.image.asAndroidBitmap();val blurred=frame.frost.asAndroidBitmap()
        val sourceWidth=source.width;val sourceHeight=source.height
        val sharp=IntArray(sourceWidth*sourceHeight);val soft=IntArray(sharp.size)
        source.getPixels(sharp,0,sourceWidth,0,0,sourceWidth,sourceHeight)
        blurred.getPixels(soft,0,sourceWidth,0,0,sourceWidth,sourceHeight)
        val result=IntArray(width*height)
        val textureScale=sourceWidth.toFloat()/frame.size.width
        val step=textureScale/scale
        val startX=(origin.x-frame.origin.x)*textureScale;val startY=(origin.y-frame.origin.y)*textureScale
        val radius=(cornerPx*scale).coerceAtMost(min(width,height)/2f)
        val depth=12f*density*scale;val strength=10f*density*scale
        val shade=(.035f+1.08f*(1-transparency).pow(2)).coerceAtMost(.88f)
        fun sample(pixels:IntArray,x:Float,y:Float):Int=pixels[y.roundToInt().coerceIn(0,sourceHeight-1)*sourceWidth+x.roundToInt().coerceIn(0,sourceWidth-1)]
        for(y in 0 until height) for(x in 0 until width) {
            val dx=x+.5f-width/2f;val dy=y+.5f-height/2f
            val qx=abs(dx)-width/2f+radius;val qy=abs(dy)-height/2f+radius
            val ox=max(qx,0f);val oy=max(qy,0f);val length=sqrt(ox*ox+oy*oy)
            val distance=length+min(max(qx,qy),0f)-radius
            if(distance>0)continue
            val inward=-distance
            val sourceX=startX+x*step;val sourceY=startY+y*step
            var color=sample(soft,sourceX,sourceY)
            var red=(color shr 16 and 255).toFloat();var green=(color shr 8 and 255).toFloat();var blue=(color and 255).toFloat()
            if(inward<depth*1.8f) {
                val nx=(if(length>0)ox/length else if(qx>qy)1f else 0f)*sign(dx)
                val ny=(if(length>0)oy/length else if(qy>=qx)1f else 0f)*sign(dy)
                val rim=(1-inward/depth).coerceIn(0f,1f)
                val angle=asin((rim*rim).coerceAtMost(.999f))
                val bend=tan(angle-asin(sin(angle)/1.45f)).coerceIn(0f,1.3f)*strength*step
                color=sample(soft,sourceX-nx*bend,sourceY-ny*bend)
                red=(color shr 16 and 255).toFloat();green=(color shr 8 and 255).toFloat();blue=(color and 255).toFloat()
                if(rim>0) {
                    val edge=sample(sharp,sourceX-nx*bend,sourceY-ny*bend)
                    val alpha=.78f*rim.pow(1.4f)
                    red=red*(1-alpha)+(edge shr 16 and 255)*alpha
                    green=green*(1-alpha)+(edge shr 8 and 255)*alpha
                    blue=blue*(1-alpha)+(edge and 255)*alpha
                }
                val facing=-nx*.65f-ny*.76f
                val glare=max(facing,0f).pow(3)*.27f+max(-facing,0f).pow(4)*.09f
                val light=(1-inward/(depth*1.8f)).coerceIn(0f,1f).pow(5)*.12f+rim*rim*glare
                red=red*(1-light)+240*light;green=green*(1-light)+247*light;blue=blue*(1-light)+255*light
            }
            red*=1-shade;green*=1-shade;blue*=1-shade
            val highlight=.025f+.02f*(1-y.toFloat()/height)
            red=red*(1-highlight)+255*highlight;green=green*(1-highlight)+255*highlight;blue=blue*(1-highlight)+255*highlight
            result[y*width+x]=(255 shl 24) or (red.roundToInt().coerceIn(0,255) shl 16) or
                (green.roundToInt().coerceIn(0,255) shl 8) or blue.roundToInt().coerceIn(0,255)
        }
        return Bitmap.createBitmap(result,width,height,Bitmap.Config.ARGB_8888)
    }
}
