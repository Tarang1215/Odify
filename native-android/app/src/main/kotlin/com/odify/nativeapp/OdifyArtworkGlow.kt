package com.odify.nativeapp

import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val neutralGlow=Color(0xFF8C96A3)
private val neutralPalette=listOf(neutralGlow,Color(0xFF697681),Color(0xFF4F5662))
private object CoverGlowCache {
    val colors=object:LinkedHashMap<String,List<Color>>(32,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,List<Color>>?)=size>64
    }
}

/** Bounded local-artwork sampling, once per cover; never decodes source audio. */
@Composable fun rememberArtworkPalette(path:String?):List<Color> {
    val value by produceState(neutralPalette,path) {
        value=withContext(Dispatchers.IO) {
            if(path.isNullOrBlank()) return@withContext neutralPalette
            synchronized(CoverGlowCache) {CoverGlowCache.colors[path]}?.let {return@withContext it}
            val color=runCatching {
                val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeFile(path,bounds)
                if(bounds.outWidth !in 1..16000 || bounds.outHeight !in 1..16000) return@runCatching neutralPalette
                var sample=1
                while(bounds.outWidth/sample>64 || bounds.outHeight/sample>64) sample*=2
                val bitmap=BitmapFactory.decodeFile(path,BitmapFactory.Options().apply {inSampleSize=sample})
                    ?: return@runCatching neutralPalette
                try {
                    val weights=FloatArray(24);val red=FloatArray(24);val green=FloatArray(24);val blue=FloatArray(24)
                    val hsv=FloatArray(3)
                    for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
                        val pixel=bitmap.getPixel(x,y)
                        if(android.graphics.Color.alpha(pixel)<200) continue
                        android.graphics.Color.colorToHSV(pixel,hsv)
                        // Near-white, black and neutral covers keep a restrained
                        // neutral glow instead of a bright white halo.
                        if(hsv[1]<.18f || hsv[2]<.14f) continue
                        val bucket=(hsv[0]/15f).toInt().coerceIn(0,23)
                        val weight=hsv[1]*hsv[1]*hsv[2]
                        weights[bucket]+=weight
                        red[bucket]+=android.graphics.Color.red(pixel)*weight
                        green[bucket]+=android.graphics.Color.green(pixel)*weight
                        blue[bucket]+=android.graphics.Color.blue(pixel)*weight
                    }
                    val selected=ArrayList<Int>()
                    for(bucket in weights.indices.sortedByDescending {weights[it]}) {
                        if(weights[bucket]<bitmap.width*bitmap.height*.01f)continue
                        if(selected.all {val d=kotlin.math.abs(it-bucket);minOf(d,24-d)>=3}) selected.add(bucket)
                        if(selected.size==3)break
                    }
                    if(selected.isEmpty()) neutralPalette
                    else selected.map {bucket -> val weight=weights[bucket]
                        Color((red[bucket]/weight/255f).coerceIn(0f,1f),
                            (green[bucket]/weight/255f).coerceIn(0f,1f),
                            (blue[bucket]/weight/255f).coerceIn(0f,1f))
                    }.toMutableList().apply {
                        while(size<3)add(lerp(first(),neutralPalette[size],.45f))
                    }
                } finally {bitmap.recycle()}
            }.getOrDefault(neutralPalette)
            synchronized(CoverGlowCache) {CoverGlowCache.colors[path]=color}
            color
        }
    }
    return value
}
