package com.odify.nativeapp

import android.graphics.*

/** A transparent material raster for launcher RemoteViews, prepared only when
 * artwork changes. It does not capture or pretend to refract the wallpaper. */
internal object OdifyWidgetGlass {
    fun render(art:Bitmap?,compact:Boolean):Bitmap {
        val w=384;val h=if(compact)80 else 168
        val result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(result);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        if(art!=null) {
            val thumb=Bitmap.createScaledBitmap(art,48,48,true)
            val blur=OdifyModalRaster.blur(thumb,10)
            paint.alpha=65;paint.isFilterBitmap=true
            canvas.drawBitmap(blur,null,Rect(0,0,w,h),paint)
            if(blur!==thumb)blur.recycle();if(thumb!==art)thumb.recycle()
        }
        paint.alpha=255
        paint.shader=LinearGradient(0f,0f,w*.6f,h.toFloat(),
            intArrayOf(0x35FFFFFF,0x04FFFFFF,0x10FFFFFF),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,w.toFloat(),h.toFloat(),paint)
        paint.shader=RadialGradient(w*.22f,-h*.42f,w*.8f,
            intArrayOf(0x24FFFFFF,0x00FFFFFF),null,Shader.TileMode.CLAMP)
        canvas.drawOval(-w*.25f,-h*1.1f,w*1.1f,h*.82f,paint)
        paint.shader=null
        return result
    }
}
