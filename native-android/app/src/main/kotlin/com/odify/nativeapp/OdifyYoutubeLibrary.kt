package com.odify.nativeapp

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class YoutubePlaylist(
    val id:String, val title:String, val count:Int, val channel:String
)
data class YoutubeVideo(
    val videoId:String, val title:String, val channel:String, val playlistId:String
)

/** Separate OAuth flow. Never stores a Google token in app preferences. */
object OdifyYoutubeAuth {
    private val scope=Scope("https://www.googleapis.com/auth/youtube.readonly")
    @Volatile private var account:Account?=null
    @Volatile private var token:String?=null
    @Volatile private var obtainedAt=0L
    val connected:Boolean get() = !token.isNullOrBlank()
    private fun request(context:Context,choose:Boolean=false):AuthorizationRequest {
        val builder=AuthorizationRequest.builder()
            .setRequestedScopes(listOf(scope))
            .setOptOutIncludingGrantedScopes(true)
        if (choose) builder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        else (account ?: context.getSharedPreferences("odify_youtube_account_v1",0)
            .getString("account",null)?.let { Account(it,"com.google") })?.let { builder.setAccount(it) }
        return builder.build()
    }
    private fun accept(context:Context,result:AuthorizationResult):Boolean {
        val value=result.accessToken ?: return false
        if(value.isBlank()) return false
        token=value
        obtainedAt=System.currentTimeMillis()
        @Suppress("DEPRECATION")
        val selected=result.toGoogleSignInAccount()?.account
        context.getSharedPreferences("odify_youtube_account_v1",0).edit().apply {
            putBoolean("enabled",true)
            if(selected!=null) {account=selected;putString("account",selected.name)}
        }.apply()
        return true
    }
    fun connect(activity:Activity, onReady:()->Unit,
                onResolution:(PendingIntent)->Unit, onError:(String)->Unit) {
        Identity.getAuthorizationClient(activity).authorize(request(activity,true))
            .addOnSuccessListener { result ->
                if(result.hasResolution() && result.pendingIntent != null)
                    onResolution(result.pendingIntent!!)
                else if(accept(activity,result)) onReady()
                else onError("YouTube 계정 권한을 승인하지 않았습니다.")
            }.addOnFailureListener { onError(it.localizedMessage ?: "YouTube 로그인 실패") }
    }
    fun resolve(activity:Activity,intent:Intent?):Boolean {
        if(intent==null) return false
        return accept(activity,Identity.getAuthorizationClient(activity)
            .getAuthorizationResultFromIntent(intent))
    }
    fun accessToken(context:Context):String {
        val t=token
        if(!t.isNullOrBlank() && System.currentTimeMillis()-obtainedAt < 35*60*1000)
            return t
        val result=Tasks.await(
            Identity.getAuthorizationClient(context).authorize(request(context)),
            30,TimeUnit.SECONDS
        )
        check(!result.hasResolution() && accept(context,result)) {
            "YouTube 계정의 재승인이 필요합니다."
        }
        return token ?: error("YouTube 로그인 필요")
    }
    fun disconnect() { token=null;account=null;obtainedAt=0L }
    fun restore(activity:Activity,onReady:()->Unit) {
        if(!activity.getSharedPreferences("odify_youtube_account_v1",0).getBoolean("enabled",false)) return
        Identity.getAuthorizationClient(activity).authorize(request(activity))
            .addOnSuccessListener { if(!it.hasResolution() && accept(activity,it)) onReady() }
    }
}

/**
 * YouTube Data API v3 only returns playlist metadata / video identifiers.
 * Actual videos open in the official YouTube player; no audio extraction.
 */
class OdifyYoutubeLibrary(private val context:Context) {
    companion object {
        private val searches = object : LinkedHashMap<String, Pair<Long, List<YoutubeVideo>>>(20, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<YoutubeVideo>>>?) = size > 20
        }
    }
    suspend fun search(query: String,limit:Int=20): List<YoutubeVideo> = withContext(Dispatchers.IO) {
        val text = query.trim()
        require(text.length in 2..160)
        require(limit in 1..50)
        val cacheKey="$limit:$text"
        synchronized(searches) { searches[cacheKey] }?.takeIf {
            System.currentTimeMillis() - it.first < 30 * 60_000
        }?.let { return@withContext it.second }
        val response = get("search?part=snippet&type=video&videoCategoryId=10&maxResults=$limit&q=${enc(text)}")
        val items = response.optJSONArray("items") ?: org.json.JSONArray()
        val result = (0 until items.length()).mapNotNull { i ->
            val item = items.getJSONObject(i)
            val id = item.optJSONObject("id")?.optString("videoId").orEmpty()
            if (!Regex("[A-Za-z0-9_-]{11}").matches(id)) null else {
                val info = item.getJSONObject("snippet")
                YoutubeVideo(id, android.text.Html.fromHtml(info.optString("title"), 0).toString(),
                    info.optString("channelTitle"), "")
            }
        }.distinctBy { it.videoId }
        synchronized(searches) { searches[cacheKey] = System.currentTimeMillis() to result }
        result
    }
    private val prefs=context.getSharedPreferences("odify_youtube_playlists_v1",0)
    val savedIds:Set<String> get()=prefs.getStringSet("saved",emptySet())?.toSet() ?: emptySet()
    fun toggleSaved(id:String) {
        val set=savedIds.toMutableSet()
        if(!set.add(id)) set.remove(id)
        prefs.edit().putStringSet("saved",set).apply()
    }
    fun savePublicLink(url:String):String {
        val uri=android.net.Uri.parse(url)
        check(uri.scheme=="https" && uri.host in
            setOf("www.youtube.com","youtube.com","music.youtube.com","m.youtube.com")) {
                "YouTube 재생목록 링크를 입력해 주세요."
        }
        val id=uri.getQueryParameter("list").orEmpty()
        check(Regex("[A-Za-z0-9_-]{10,160}").matches(id)) {
            "올바른 YouTube 플레이리스트 ID가 아닙니다."
        }
        prefs.edit().putStringSet("saved",savedIds+id).apply()
        return id
    }

    suspend fun ownedPublic():List<YoutubePlaylist> = withContext(Dispatchers.IO) {
        val all=ArrayList<YoutubePlaylist>()
        var token:String?=null
        for(page in 0 until 10) {
            val q="part=snippet,contentDetails,status&mine=true&maxResults=50" +
                (token?.let { "&pageToken=${enc(it)}" } ?: "")
            val response=get("playlists?$q")
            val a=response.optJSONArray("items") ?: break
            for(i in 0 until a.length()) {
                val p=a.getJSONObject(i)
                if(p.optJSONObject("status")?.optString("privacyStatus")!="public") continue
                val info=p.optJSONObject("snippet") ?: continue
                all += YoutubePlaylist(
                    id=p.optString("id"),title=info.optString("title"),
                    count=p.optJSONObject("contentDetails")?.optInt("itemCount") ?: 0,
                    channel=info.optString("channelTitle")
                )
            }
            token=response.optString("nextPageToken").takeIf(String::isNotBlank) ?: break
        }
        all
    }

    suspend fun playlist(id:String):List<YoutubeVideo> = withContext(Dispatchers.IO) {
        require(Regex("[A-Za-z0-9_-]{10,160}").matches(id))
        val result=ArrayList<YoutubeVideo>()
        var token:String?=null
        for(page in 0 until 20) {
            val q="part=snippet,contentDetails&playlistId=${enc(id)}&maxResults=50" +
                (token?.let { "&pageToken=${enc(it)}" } ?: "")
            val response=get("playlistItems?$q")
            val a=response.optJSONArray("items") ?: break
            for(i in 0 until a.length()) {
                val x=a.getJSONObject(i)
                val detail=x.optJSONObject("contentDetails")
                val videoId=detail?.optString("videoId").orEmpty()
                if(!Regex("[A-Za-z0-9_-]{11}").matches(videoId)) continue
                val snippet=x.optJSONObject("snippet") ?: continue
                val name=snippet.optString("title")
                if(name=="Deleted video" || name=="Private video") continue
                result += YoutubeVideo(videoId,name,snippet.optString("videoOwnerChannelTitle"),id)
            }
            token=response.optString("nextPageToken").takeIf(String::isNotBlank) ?: break
        }
        result
    }

    private fun enc(s:String)=URLEncoder.encode(s,"UTF-8")
    private fun get(path:String):JSONObject {
        val url=URL("https://www.googleapis.com/youtube/v3/$path")
        val conn=url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout=12_000;conn.readTimeout=25_000
            conn.setRequestProperty("Authorization","Bearer ${OdifyYoutubeAuth.accessToken(context)}")
            val status=conn.responseCode
            if(status!=200) {
                val body=conn.errorStream?.bufferedReader()?.use { it.readText().take(4096) }.orEmpty()
                val reason=runCatching { JSONObject(body).getJSONObject("error").getJSONArray("errors")
                    .getJSONObject(0).getString("reason") }.getOrDefault("unknown")
                val consumer=Regex("(?:project[s]?[/ ]+)([0-9]{5,})",RegexOption.IGNORE_CASE)
                    .find(body)?.groupValues?.get(1).orEmpty()
                context.getSharedPreferences("odify_youtube_account_v1",0).edit()
                    .putString("lastApiError","HTTP $status · $reason").apply()
                context.getSharedPreferences("odify_youtube_account_v1",0).edit()
                    .putString("lastApiConsumerProject",consumer).apply()
                android.util.Log.w("OdifyYouTube","Data API HTTP $status reason=$reason consumerProject=$consumer")
                val message=when(reason) {
                    "accessNotConfigured", "serviceDisabled" -> "YouTube API 설정 연결 오류" +
                        (if(consumer.isNotBlank()) " · 프로젝트 $consumer" else "") + ". 앱 OAuth와 콘솔의 프로젝트를 확인해 주세요."
                    "quotaExceeded", "dailyLimitExceeded", "rateLimitExceeded" -> "YouTube 검색 한도를 사용했습니다. YouTube 앱에서 검색할 수 있어요."
                    "insufficientPermissions", "authError" -> "YouTube 계정을 다시 연결해 주세요."
                    else -> "YouTube 검색 연결 오류 ($status · $reason)"
                }
                error(message)
            }
            val response=JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            context.getSharedPreferences("odify_youtube_account_v1",0).edit()
                .remove("lastApiError").remove("lastApiConsumerProject").apply()
            return response
        } finally {conn.disconnect()}
    }
}
