package com.odify.nativeapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.work.*
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

internal object OdifyPhoneDownloads {
    val version=MutableStateFlow(0L)
    fun schedule(context:Context,update:Boolean=false) {
        WorkManager.getInstance(context).enqueueUniqueWork("odify-phone-audio",ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<OdifyPhoneDownloadWorker>()
                .setInputData(workDataOf("update" to update))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}

internal object OdifyPhoneDownloadPolicy {
    fun ageRestricted(message:String)=listOf("age-restricted","age restricted","confirm your age","sign in to confirm your age").any {message.contains(it,true)}
    fun gated(message:String)=listOf("not a bot","captcha","sign in","429","403").any {message.contains(it,true)}
}

/** Serialized durable downloads, independent of Activity/PC. No Drive uploads occur here. */
class OdifyPhoneDownloadWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun getForegroundInfo():ForegroundInfo {
        val manager=applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("odify_audio_save","음악 저장",NotificationManager.IMPORTANCE_LOW))
        val notification=Notification.Builder(applicationContext,"odify_audio_save").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Odify · 음악 보관").setContentText("휴대폰에 저장 중 · Drive 업로드 보류")
            .setOngoing(true).build()
        return if(Build.VERSION.SDK_INT>=29) ForegroundInfo(8010,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(8010,notification)
    }
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val requests=OdifyStreamingRequests(applicationContext)
        try {
            val entries=requests.read()
            val pending=setOf("QUEUED_PC","QUEUED_PHONE","DOWNLOADING_PHONE")
            if(!inputData.getBoolean("update",false) && (0 until entries.length()).none {entries.getJSONObject(it).optString("status") in pending})
                return@withContext Result.success()
            setForeground(getForegroundInfo())
            YoutubeDL.init(applicationContext);FFmpeg.init(applicationContext)
            val engine=applicationContext.getSharedPreferences("odify_phone_engine_v1",0)
            val force=inputData.getBoolean("update",false)
            if(engine.getBoolean("source_hold",false) && !force)return@withContext Result.success()
            if(force || System.currentTimeMillis()-engine.getLong("checked",0)>24L*60*60*1000) {
                runCatching {YoutubeDL.updateYoutubeDL(applicationContext)}.onSuccess {
                    engine.edit().putLong("checked",System.currentTimeMillis()).apply()
                    if(force) {engine.edit().putBoolean("source_hold",false).apply();requests.retryFailed()}
                }.onFailure {android.util.Log.w("OdifyPhoneSave","Engine update failed",it)}
            }
            val current=requests.read()
            for(i in 0 until current.length()) {
                if(isStopped) return@withContext Result.success()
                val row=current.getJSONObject(i)
                if(row.optString("status") !in pending) continue
                val id=row.getString("video_id")
                require(Regex("[A-Za-z0-9_-]{11}").matches(id))
                val month=java.time.Instant.parse(row.getString("requested_at")).atZone(java.time.ZoneId.systemDefault())
                val root=applicationContext.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: applicationContext.filesDir
                val folder=File(root,"YouTube/${month.year}/${month.monthValue.toString().padStart(2,'0')}").apply {mkdirs()}
                if(folder.usableSpace<256L*1024*1024) {
                    requests.update(id){it.put("status","HELD_SPACE").put("error","휴대폰 여유 공간 256MB 필요")}
                    continue
                }
                requests.update(id){it.put("status","DOWNLOADING_PHONE").put("error","")}
                try {
                    val request=YoutubeDLRequest("https://www.youtube.com/watch?v=$id")
                        .addOption("--no-playlist").addOption("--no-overwrites").addOption("--no-mtime")
                        .addOption("--socket-timeout",15).addOption("--retries",1).addOption("--fragment-retries",1)
                        .addOption("--max-filesize","128M").addOption("--write-info-json")
                        .addOption("-f","bestaudio[ext=m4a]/bestaudio").addOption("-x")
                        .addOption("--audio-format","m4a").addOption("--embed-metadata").addOption("--embed-thumbnail")
                        .addOption("-o",File(folder,"YT_$id.%(ext)s").absolutePath)
                    var lastProgress=-1
                    YoutubeDL.execute(request,processId="odify-$id",redirectErrorStream=false,callback={progress,_,_ ->
                        val step=progress.toInt().coerceIn(0,99)
                        if(step/5!=lastProgress/5 || lastProgress<0) {
                            lastProgress=step
                            requests.update(id){it.put("progress",step)}
                        }
                    })
                    val output=File(folder,"YT_$id.m4a")
                    check(output.isFile && output.length() in 1024..128L*1024*1024) {"완성된 음원 파일이 없습니다."}
                    val info=org.json.JSONObject(File(folder,"YT_$id.info.json").readText())
                    check(info.getString("id")==id) {"영상 ID가 일치하지 않습니다."}
                    val media=MediaMetadataRetriever()
                    val duration=try {media.setDataSource(output.absolutePath)
                        media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } finally {media.release()}
                    val expected=row.optLong("duration_ms")
                    check(duration>0 && (expected<=0 || kotlin.math.abs(duration-expected)<=5000)) {"저장 파일 재생 시간 검증 실패"}
                    val digest=output.inputStream().use {stream ->
                        val sha=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
                        while(true) {val count=stream.read(buffer);if(count<0)break;sha.update(buffer,0,count)}
                        sha.digest().joinToString(""){"%02x".format(it)}
                    }
                    requests.update(id){it.put("status","SAVED_PHONE").put("local_path",output.absolutePath)
                        .put("sha256",digest).put("duration_ms",duration).put("progress",100).put("error","").put("drive_upload",false)}
                    OdifyPhoneDownloads.version.value++
                } catch(failure:Exception) {
                    if(failure is kotlinx.coroutines.CancellationException) {
                        YoutubeDL.destroyProcessById("odify-$id");throw failure
                    }
                    val message=(failure.message?.takeIf {it.isNotBlank()} ?: "${failure.javaClass.simpleName}: 다운로드 엔진에서 상세 오류를 반환하지 않았습니다.").takeLast(1200)
                    val gated=OdifyPhoneDownloadPolicy.gated(message)
                    android.util.Log.w("OdifyPhoneSave",message,failure)
                    requests.update(id){it.put("status",if(OdifyPhoneDownloadPolicy.ageRestricted(message))"SKIPPED_AGE" else if(gated)"HELD_SOURCE" else "HELD_PHONE")
                        .put("error",message)}
                    // A request for authentication/verification pauses this batch.
                    if(gated) {engine.edit().putBoolean("source_hold",true).apply();break}
                }
            }
            Result.success()
        } catch(cancel:kotlinx.coroutines.CancellationException) {throw cancel}
        catch(failure:Exception) {android.util.Log.w("OdifyPhoneSave","Download engine unavailable",failure);Result.failure()}
    }
}
