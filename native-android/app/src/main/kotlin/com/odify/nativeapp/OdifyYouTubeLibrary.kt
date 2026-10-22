package com.odify.nativeapp

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** YouTube authorization and account selection are separate from Drive. */
object OdifyYouTubeAuth {
    private const val SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
    @Volatile private var cached: String? = null
    @Volatile private var obtainedAt: Long = 0L
    @Volatile private var chosenAccount: String? = null

    fun signInIntent(activity: Activity): Intent {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail().build()
        return GoogleSignIn.getClient(activity,options).signInIntent
    }

    fun consumeSignIn(data: Intent?): String {
        val account = Tasks.await(GoogleSignIn.getSignedInAccountFromIntent(data))
        val email = account.email ?: error("선택한 YouTube 계정 이메일을 확인할 수 없습니다.")
        chosenAccount = email
        cached = null
        return email
    }

    private fun request(): AuthorizationRequest {
        val email = chosenAccount ?: error("먼저 YouTube용 Google 계정을 선택하세요.")
        return AuthorizationRequest.builder()
            .setAccount(Account(email,"com.google"))
            .setRequestedScopes(listOf(Scope(SCOPE)))
            .build()
    }

    fun authorize(
        activity: Activity,
        onToken: () -> Unit,
        onConsent: (android.app.PendingIntent) -> Unit,
        onError: (String) -> Unit
    ) {
        val req = try { request() } catch(e:Exception) { onError(e.message.orEmpty());return }
        Identity.getAuthorizationClient(activity).authorize(req)
            .addOnSuccessListener { r ->
                if(r.hasResolution() && r.pendingIntent != null) {
                    onConsent(r.pendingIntent!!)
                } else if(!r.accessToken.isNullOrBlank()) {
                    cached = r.accessToken!!
                    obtainedAt = System.currentTimeMillis()
                    onToken()
                } else onError("YouTube 조회 권한을 받지 못했습니다.")
            }.addOnFailureListener { onError(it.localizedMessage ?: "YouTube OAuth 오류") }
    }

    fun resolve(activity: Activity, data: Intent?): Boolean {
        val token = runCatching {
            Identity.getAuthorizationClient(activity)
                .getAuthorizationResultFromIntent(data).accessToken
        }.getOrNull() ?: return false
        cached = token
        obtainedAt = System.currentTimeMillis()
        return true
    }

    fun token(context: Context): String {
        cached?.takeIf { System.currentTimeMillis()-obtainedAt < 40*60*1000 }?.let {
            return it
        }
        val result = Tasks.await(
            Identity.getAuthorizationClient(context).authorize(request()),
            20, TimeUnit.SECONDS
        )
        if(result.hasResolution()) error("YouTube 연결 버튼에서 계정 권한을 다시 승인하세요.")
        return result.accessToken?.also {
            cached = it
            obtainedAt = System.currentTimeMillis()
        } ?: error("YouTube 액세스 토큰이 없습니다.")
    }

    fun accountEmail(): String? = chosenAccount
}

data class OdifyYouTubePlaylist(val id:String,val title:String,
    val channel:String,val count:Int, val thumbnail:String)
data class OdifyYouTubeVideo(val id:String,val title:String,val channel:String,
    val thumbnail:String)

/**
 * Official YouTube Data API, read-only. Stores only public playlist metadata
 * and video IDs; never downloads YouTube audio or bypasses embedded playback.
 */
class OdifyYouTubeLibrary(private val context: Context) {
    private val prefs=context.getSharedPreferences("odify_youtube_playlists",0)
    private val idPattern=Regex("^[A-Za-z0-9_-]{8,90}$")
    private val videoPattern=Regex("^[A-Za-z0-9_-]{11}$")

    fun savedPlaylists(): List<OdifyYouTubePlaylist> {
        return runCatching {
            val a=JSONArray(prefs.getString("saved","[]").orEmpty())
            (0 until a.length()).mapNotNull { n ->
                val x=a.optJSONObject(n) ?: return@mapNotNull null
                val id=x.optString("id")
                if (!idPattern.matches(id)) return@mapNotNull null
                OdifyYouTubePlaylist(id,x.optString("title"),x.optString("channel"),
                    x.optInt("count"),x.optString("thumbnail"))
            }
        }.getOrDefault(emptyList())
    }
    fun isSaved(id:String):Boolean = savedPlaylists().any { it.id==id }
    fun toggleSave(playlist:OdifyYouTubePlaylist): Boolean {
        require(idPattern.matches(playlist.id))
        val old=savedPlaylists().toMutableList()
        val remove=old.removeAll { it.id==playlist.id }
        if(!remove) old.add(playlist)
        val a=JSONArray()
        old.forEach { item ->
            a.put(JSONObject().apply{
                put("id",item.id);put("title",item.title);put("channel",item.channel)
                put("count",item.count);put("thumbnail",item.thumbnail)
            })
        }
        prefs.edit().putString("saved",a.toString()).apply()
        return !remove
    }

    suspend fun loadPublicPlaylists(): List<OdifyYouTubePlaylist> =
        withContext(Dispatchers.IO) {
            val results=LinkedHashMap<String,OdifyYouTubePlaylist>()
            var next=""
            repeat(5) {
                val params="part=snippet,status,contentDetails&mine=true&maxResults=50" +
                    if(next.isNotBlank()) "&pageToken=${enc(next)}" else ""
                val obj=request("playlists?$params")
                val a=obj.optJSONArray("items") ?: JSONArray()
                for(i in 0 until a.length()) {
                    val row=a.optJSONObject(i) ?: continue
                    val id=row.optString("id")
                    if(!idPattern.matches(id)) continue
                    if(row.optJSONObject("status")?.optString("privacyStatus")!="public") continue
                    val snippet=row.optJSONObject("snippet") ?: continue
                    results[id]=OdifyYouTubePlaylist(
                        id,snippet.optString("title"),snippet.optString("channelTitle"),
                        row.optJSONObject("contentDetails")?.optInt("itemCount") ?: 0,
                        snippet.optJSONObject("thumbnails")?.optJSONObject("medium")
                            ?.optString("url").orEmpty()
                    )
                }
                next=obj.optString("nextPageToken")
                if(next.isBlank()) return@withContext results.values.toList()
            }
            results.values.toList()
        }

    suspend fun loadVideos(playlistId:String):List<OdifyYouTubeVideo> =
        withContext(Dispatchers.IO) {
            require(idPattern.matches(playlistId)) { "YouTube 플레이리스트 ID가 잘못되었습니다." }
            val result=LinkedHashMap<String,OdifyYouTubeVideo>()
            var next=""
            repeat(10) {
                val params="part=snippet&playlistId=${enc(playlistId)}&maxResults=50" +
                    if(next.isNotBlank()) "&pageToken=${enc(next)}" else ""
                val obj=request("playlistItems?$params")
                val a=obj.optJSONArray("items") ?: JSONArray()
                for(i in 0 until a.length()) {
                    val item=a.optJSONObject(i)?.optJSONObject("snippet") ?: continue
                    val id=item.optJSONObject("resourceId")?.optString("videoId").orEmpty()
                    if(!videoPattern.matches(id)) continue
                    val title=item.optString("title")
                    if(title=="Private video" || title=="Deleted video") continue
                    result[id]=OdifyYouTubeVideo(id,title,
                        item.optString("videoOwnerChannelTitle",item.optString("channelTitle")),
                        item.optJSONObject("thumbnails")?.optJSONObject("medium")
                            ?.optString("url").orEmpty())
                }
                next=obj.optString("nextPageToken")
                if(next.isBlank()) return@withContext result.values.toList()
            }
            result.values.toList()
        }

    private fun request(endpoint:String):JSONObject {
        require(endpoint.startsWith("playlists?")||endpoint.startsWith("playlistItems?"))
        val conn=URL("https://www.googleapis.com/youtube/v3/$endpoint")
            .openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Authorization",
                "Bearer ${OdifyYouTubeAuth.token(context)}")
            conn.connectTimeout=12_000;conn.readTimeout=18_000
            if(conn.responseCode!=200) {
                val code=conn.responseCode
                error("YouTube 목록 조회 HTTP $code · YouTube Data API 활성화와 계정 권한을 확인하세요.")
            }
            val bytes=conn.inputStream.use{it.readNBytes(2_000_001)}
            require(bytes.size<=2_000_000) { "YouTube 응답 제한 초과" }
            return JSONObject(String(bytes,Charsets.UTF_8))
        } finally { conn.disconnect() }
    }
    private fun enc(v:String)=URLEncoder.encode(v,"UTF-8")
}
