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
    private fun request(choose:Boolean=false):AuthorizationRequest {
        val builder=AuthorizationRequest.builder()
            .setRequestedScopes(listOf(scope))
            .setOptOutIncludingGrantedScopes(true)
        if (choose) builder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        else account?.let { builder.setAccount(it) }
        return builder.build()
    }
    private fun accept(result:AuthorizationResult):Boolean {
        val value=result.accessToken ?: return false
        if(value.isBlank()) return false
        token=value
        obtainedAt=System.currentTimeMillis()
        @Suppress("DEPRECATION")
        val selected=result.toGoogleSignInAccount()?.account
        if(selected!=null) account=selected
        return true
    }
    fun connect(activity:Activity, onReady:()->Unit,
                onResolution:(PendingIntent)->Unit, onError:(String)->Unit) {
        Identity.getAuthorizationClient(activity).authorize(request(true))
            .addOnSuccessListener { result ->
                if(result.hasResolution() && result.pendingIntent != null)
                    onResolution(result.pendingIntent!!)
                else if(accept(result)) onReady()
                else onError("YouTube 계정 권한을 승인하지 않았습니다.")
            }.addOnFailureListener { onError(it.localizedMessage ?: "YouTube 로그인 실패") }
    }
    fun resolve(activity:Activity,intent:Intent?):Boolean {
        if(intent==null) return false
        return accept(Identity.getAuthorizationClient(activity)
            .getAuthorizationResultFromIntent(intent))
    }
    fun accessToken(context:Context):String {
        val t=token
        if(!t.isNullOrBlank() && System.currentTimeMillis()-obtainedAt < 35*60*1000)
            return t
        val result=Tasks.await(
            Identity.getAuthorizationClient(context).authorize(request()),
            30,TimeUnit.SECONDS
        )
        check(!result.hasResolution() && accept(result)) {
            "YouTube 계정의 재승인이 필요합니다."
        }
        return token ?: error("YouTube 로그인 필요")
    }
    fun disconnect() { token=null;account=null;obtainedAt=0L }
}

/**
 * YouTube Data API v3 only returns playlist metadata / video identifiers.
 * Actual videos open in the official YouTube player; no audio extraction.
 */
class OdifyYoutubeLibrary(private val context:Context) {
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
            check(status==200) {
                val body=conn.errorStream?.bufferedReader()?.use { it.readText().take(250) }
                "YouTube Data API HTTP $status ${body.orEmpty()}"
            }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {conn.disconnect()}
    }
}
