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
            paint.alpha=20;paint.isFilterBitmap=true
            canvas.drawBitmap(blur,null,Rect(0,0,w,h),paint)
            if(blur!==thumb)blur.recycle();if(thumb!==art)thumb.recycle()
        }
        paint.alpha=255
        paint.shader=LinearGradient(0f,0f,w*.6f,h.toFloat(),
            intArrayOf(0x14FFFFFF,0x00FFFFFF,0x08FFFFFF),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,w.toFloat(),h.toFloat(),paint)
        paint.shader=RadialGradient(w*.05f,-h*.9f,w*.72f,
            intArrayOf(0x1AFFFFFF,0x00FFFFFF),null,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,w.toFloat(),h.toFloat(),paint)
        paint.shader=LinearGradient(0f,0f,w.toFloat(),h.toFloat(),
            intArrayOf(0x40FFFFFF,0x00FFFFFF,0x28FFFFFF),floatArrayOf(0f,.52f,1f),Shader.TileMode.CLAMP)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1.3f
        canvas.drawRoundRect(1.6f,1.6f,w-1.6f,h-1.6f,25f,25f,paint)
        paint.style=Paint.Style.FILL
        paint.shader=null
        return result
    }
}
