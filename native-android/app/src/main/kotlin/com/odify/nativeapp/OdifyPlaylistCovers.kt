package com.odify.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.File
import java.util.UUID

/** Private, bounded cover copies survive picker grants and never touch track artwork. */
internal object OdifyPlaylistCovers {
    fun import(context:Context,uri:Uri):String {
        val staged=File.createTempFile("playlist-import-",".image",context.cacheDir)
        var output:File?=null
        try {
            context.contentResolver.openInputStream(uri)?.use {input ->
                staged.outputStream().use {destination ->
                    val buffer=ByteArray(8192);var total=0L
                    while(true) {
                        val count=input.read(buffer);if(count<0)break
                        total+=count;require(total<=20L*1024*1024) {"20MB 이하의 이미지를 선택해 주세요."}
                        destination.write(buffer,0,count)
                    }
                }
            } ?: error("이미지를 열 수 없습니다.")
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeFile(staged.path,bounds)
            require(bounds.outWidth in 1..32768 && bounds.outHeight in 1..32768) {"지원되는 이미지 파일을 선택해 주세요."}
            val bitmap=if(Build.VERSION.SDK_INT>=28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(staged)) {decoder,info,_ ->
                    val scale=1024f/maxOf(info.size.width,info.size.height).coerceAtLeast(1024)
                    decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),(info.size.height*scale).toInt().coerceAtLeast(1))
                    decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                var sample=1
                while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1024)sample*=2
                BitmapFactory.decodeFile(staged.path,BitmapFactory.Options().apply {inSampleSize=sample})
                    ?: error("이미지를 읽을 수 없습니다.")
            }
            val directory=File(context.filesDir,"playlist-covers").apply {check(isDirectory || mkdirs())}
            output=File(directory,"${UUID.randomUUID()}.jpg")
            try {output.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.JPEG,91,it))}}
            finally {bitmap.recycle()}
            return output.absolutePath
        } catch(error:Exception) {output?.delete();throw error}
        finally {staged.delete()}
    }

    fun removeOwned(context:Context,path:String?) {
        if(path.isNullOrBlank())return
        val file=File(path)
        if(file.canonicalFile.parentFile==File(context.filesDir,"playlist-covers").canonicalFile)file.delete()
    }
}

internal fun OdifyPlaylist.coverSong(tracks:List<OdifySong>):OdifySong? {
    val original=tracks.firstOrNull {!it.artworkPath.isNullOrBlank()} ?: tracks.firstOrNull()
    return coverPath?.let {path ->
        (original ?: OdifySong("playlist:$id",name,"","","")).copy(id="playlist:$id",artworkPath=path)
    } ?: original
}
