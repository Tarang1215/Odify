package com.odify.nativeapp

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

internal class OdifyChartRequests(context:Context,fileName:String="chart_collection_requests.json") {
    companion object {private val lock=Any()}
    private val file=android.util.AtomicFile(File(context.filesDir,fileName))
    fun read():JSONArray=synchronized(lock) {runCatching {JSONObject(file.readFully().toString(Charsets.UTF_8)).getJSONArray("requests")}.getOrDefault(JSONArray())}
    private fun save(rows:JSONArray) {
        val stream=file.startWrite()
        try {stream.write(JSONObject().put("requests",rows).toString().toByteArray());file.finishWrite(stream)}
        catch(failure:Exception){file.failWrite(stream);throw failure}
    }
    fun observe(chart:ExploreChart,index:OdifyChartMatching.Index)=synchronized(lock) {
        val rows=read();val known=(0 until rows.length()).associateBy {rows.getJSONObject(it).getString("chart_id")}
        for(track in chart.tracks) {
            if(index.resolve(track)!=null || track.id in known)continue
            rows.put(JSONObject().put("chart_id",track.id).put("country",chart.country).put("title",track.title)
                .put("artist",track.artist).put("first_seen",java.time.Instant.now().toString()).put("status","AWAITING_LINK"))
        }
        save(rows)
    }
    fun resolved(id:String,videoId:String)=synchronized(lock) {
        val rows=read()
        for(i in 0 until rows.length())rows.getJSONObject(i).takeIf {it.optString("chart_id")==id}
            ?.put("status","QUEUED_PHONE")?.put("video_id",videoId)
        save(rows)
    }
}

internal object OdifyChartAutomation {
    fun schedule(context:Context) {
        val prefs=context.getSharedPreferences("odify_chart_schedule",0)
        val days=if(prefs.getBoolean("weekly",false))7L else 1L
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("odify-chart-refresh",ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<OdifyChartRefreshWorker>(days,TimeUnit.DAYS)
                .setInitialDelay(30,TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build())
    }
    fun now(context:Context) {WorkManager.getInstance(context).enqueueUniqueWork("odify-chart-refresh-now",ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<OdifyChartRefreshWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())}
}

/** Refresh metadata daily/weekly. Only new unowned entries use the bounded
 * YouTube lookup budget. Authentication/rate failures remain pending. */
class OdifyChartRefreshWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val context=applicationContext;val prefs=context.getSharedPreferences("odify_chart_schedule",0)
        val requests=OdifyChartRequests(context);val repository=OdifyExploreRepository(context)
        val streaming=OdifyStreamingLibrary(context)
        val catalog=DriveCatalog(context).tracks()+OdifyLibrary(context).songs()+OdifyPhoneFolder(context).tracks()+streaming.all()
        val index=OdifyChartMatching.buildIndex(catalog)
        var refreshed=0
        for(country in listOf("kr","jp","us")) {
            if(isStopped)return@withContext Result.success()
            runCatching {repository.load(country,true)}.onSuccess {requests.observe(it,index);refreshed++}
                .onFailure {prefs.edit().putString("error",it.message ?: "차트 연결 오류").apply()}
        }
        val today=java.time.LocalDate.now().toString()
        var used=if(prefs.getString("budget_day","")==today)prefs.getInt("lookups",0) else 0
        val rows=requests.read()
        var scheduled=false
        if(OdifyYoutubeAuth.connected) for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i)
            if(used>=12 || isStopped)break
            if(row.optString("status")!="AWAITING_LINK")continue
            try {
                used++;prefs.edit().putString("budget_day",today).putInt("lookups",used).apply()
                val video=OdifyYoutubeQuickPlay.first(OdifyYoutubeLibrary(context).search(
                    OdifyYoutubeQuickPlay.query(row.getString("artist"),row.getString("title")))) ?: continue
                streaming.remember(video);streaming.associateChart(video.videoId,row.getString("chart_id"))
                OdifyStreamingRequests(context).enqueue(video,0,"new_chart_entry")
                requests.resolved(row.getString("chart_id"),video.videoId);scheduled=true
            } catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
            catch(failure:Exception){prefs.edit().putString("error",failure.message ?: "음원 검색 연결 오류").apply();break}
        }
        if(scheduled)OdifyPhoneDownloads.schedule(context)
        prefs.edit().putLong("last_refresh",System.currentTimeMillis()).putInt("refreshed",refreshed).apply()
        if(refreshed>0)Result.success() else Result.retry()
    }
}
