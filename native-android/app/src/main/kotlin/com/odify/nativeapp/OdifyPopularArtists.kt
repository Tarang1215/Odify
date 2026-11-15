package com.odify.nativeapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class OdifyPopularArtist(val name:String,val rank:Int,val url:String)
internal data class OdifyArtistRanking(val country:String,val artists:List<OdifyPopularArtist>,val fetchedAt:Long,val cached:Boolean=false)
internal class OdifyLastFmError(val code:Int,message:String):IllegalStateException(message)

/** User-supplied read API key; never written to source, URLs in logs or backup. */
internal class OdifyLastFmKeyStore(context:Context,private val name:String="odify_lastfm_key") {
    private val prefs=context.getSharedPreferences(name,0)
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(name,null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(name,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read():String?=runCatching {
        val raw=prefs.getString("cipher",null) ?: return null
        val iv=prefs.getString("iv",null) ?: return null
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(raw,Base64.NO_WRAP)),Charsets.UTF_8).takeIf {it.matches(Regex("[a-fA-F0-9]{32}"))}
    }.getOrNull()
    fun save(value:String) {
        require(value.matches(Regex("[a-fA-F0-9]{32}")))
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        check(prefs.edit().putString("cipher",Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP))
            .putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).commit())
        revision.value++
    }
    fun clear() {prefs.edit().clear().commit();revision.value++}
    companion object {val revision=MutableStateFlow(0)}
}

internal class OdifyPopularArtists(private val context:Context) {
    suspend fun load(country:String,force:Boolean=false):OdifyArtistRanking=withContext(Dispatchers.IO) { cacheLock.withLock {
        countryName(country)
        val apiKey=OdifyLastFmKeyStore(context).read() ?: error("프로필에서 Last.fm API 키를 연결해 주세요.")
        val file=File(context.cacheDir,"lastfm_artists_$country.json")
        val saved=runCatching {val data=JSONObject(file.readText());check(data.getString("country")==country)
            parse(data.getString("response"),country,data.getLong("fetchedAt"),true)}.getOrNull()
        if(!force && saved!=null && System.currentTimeMillis()-saved.fetchedAt in 0 until 6*60*60_000L)return@withLock saved
        try {
            val raw=request("geo.gettopartists","&country="+URLEncoder.encode(countryName(country),"UTF-8"),apiKey)
            val result=parse(raw,country,System.currentTimeMillis())
            val atomic=AtomicFile(file);val stream=atomic.startWrite()
            try {stream.write(JSONObject().put("country",country).put("fetchedAt",result.fetchedAt).put("response",raw).toString().toByteArray());atomic.finishWrite(stream)}
            catch(error:Exception){atomic.failWrite(stream);throw error}
            result
        } catch(cancel:CancellationException){throw cancel}
        catch(error:Exception){saved ?: throw error}
    } }
    suspend fun validate(apiKey:String)=withContext(Dispatchers.IO) {
        val root=response(request("chart.gettopartists","",apiKey,1))
        check(root.optJSONObject("artists")?.optJSONArray("artist")?.length()==1) {"Last.fm 응답을 확인하지 못했어요."}
    }
    companion object {
    internal fun request(method:String,parameters:String,apiKey:String,limit:Int=30):String {
        require(apiKey.matches(Regex("[a-fA-F0-9]{32}"))) {"API 키 형식을 확인해 주세요."}
        val address="https://ws.audioscrobbler.com/2.0/?method=$method$parameters&limit=$limit&format=json&api_key=$apiKey"
        val connection=URL(address).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout=8000;connection.readTimeout=10000;connection.instanceFollowRedirects=false
            connection.setRequestProperty("User-Agent","Odify/1.0")
            val code=connection.responseCode
            val body=if(code==200)connection.inputStream else connection.errorStream
            val raw=body?.use {input ->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) {val count=input.read(buffer);if(count<0)break
                    check(output.size()+count<=512_000) {"Last.fm 응답을 확인하지 못했어요."}
                    output.write(buffer,0,count)}
                String(output.toByteArray(),Charsets.UTF_8)
            }.orEmpty()
            response(raw,code)
            raw
        } catch(error:java.io.IOException) {
            throw OdifyLastFmError(-1,"Last.fm에 연결하지 못했어요. 네트워크를 확인해 주세요.")
        } finally {connection.disconnect()}
    }
        private val cacheLock=Mutex()
        fun countryName(code:String)=when(code) {"kr"->"Korea, Republic of";"jp"->"Japan";"us"->"United States";else->error("지원하지 않는 국가입니다.")}
        internal fun response(raw:String,httpCode:Int=200):JSONObject {
            val root=runCatching {JSONObject(raw)}.getOrNull()
            val code=root?.optInt("error") ?: 0
            if(code!=0 || httpCode!=200 || root==null)throw OdifyLastFmError(code,when(code) {
                10,26 -> "Last.fm API 키를 확인해 주세요."
                29 -> "Last.fm 요청이 많아요. 잠시 후 다시 시도해 주세요."
                6,7 -> "Last.fm에서 해당 국가의 순위를 찾지 못했어요."
                else -> "Last.fm에서 국가별 순위를 불러오지 못했어요."
            })
            return root
        }
        fun parse(raw:String,country:String,at:Long,cached:Boolean=false):OdifyArtistRanking {
            countryName(country)
            val root=response(raw)
            val data=root.getJSONObject("topartists").getJSONArray("artist")
            val names=HashSet<String>()
            val artists=(0 until minOf(data.length(),30)).mapNotNull {i ->
                val row=data.optJSONObject(i) ?: return@mapNotNull null
                val name=row.optString("name").trim();val url=row.optString("url")
                if(name.isBlank() || !names.add(OdifyMusicMatching.artistKey(name)))null
                else OdifyPopularArtist(OdifyMusicMatching.artistName(name),i+1,url.takeIf {runCatching {URL(it).let {u ->u.protocol=="https" && u.host=="www.last.fm"}}.getOrDefault(false)}.orEmpty())
            }
            check(artists.isNotEmpty()) {"Last.fm에 표시할 국가별 아티스트가 없어요."}
            return OdifyArtistRanking(country,artists,at,cached)
        }
    }
}
