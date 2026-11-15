package com.odify.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/** Recording identity is independent of its chosen YouTube transport. */
data class OdifyMusicIdentity(val id:String,val title:String,val artist:String,val album:String,
    val artwork:String,val artistId:String,val artistUrl:String,val trackUrl:String,
    val durationMs:Long=0,val genre:String="",val albumUrl:String="",val compilation:Boolean=false) {
    fun track(rank:Int=0)=ExploreTrack(id,title,artist,artwork,listOf(genre).filter(String::isNotBlank),rank,music=this)
}

internal object OdifyMusicMatching {
    data class Credits(val title: String, val artist: String)

    private val marks=Regex("\\p{M}+")
    private val nonWord=Regex("[^\\p{L}\\p{N}]")
    fun cacheKey(value:String)=java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {"%02x".format(it)}
    fun norm(value:String)=Normalizer.normalize(value,Normalizer.Form.NFKD).replace(marks,"")
        .lowercase(Locale.ROOT).replace(nonWord,"")
    private val badge=Regex("(?i)^(?:(?:official|music video|video|audio|lyrics?|visuali[sz]er|mv|m/v|hd|4k|color coded|가사|발음|한글\\s*자막|해석|歌詞|[\\s/_-]))+$")
    private val brackets=Regex("[\\[（(【]([^\\]）)】]*)[\\]）)】]")
    private val split=Regex("\\s+[-–—]\\s+")
    private val suffix=Regex("(?i)\\s*(?:-\\s*Topic|\\s+Official|VEVO)$")
    private val producer=Regex("(?i)\\s*[(（]\\s*prod\\.?\\s+(?:by\\s+)?([^()（）]+)[)）]")
    private val featuring=Regex("(?i)\\s+[\\[(（]?\\s*(?:feat(?:uring)?|ft)\\.?\\s+.+$")
    private val quotedTitle=Regex("""^(.+?)\s+['‘"“](.+)['’"”]$""")
    private val creditedAlias=Regex("""^([^()]+)\s*\(([^()]+)\)$""")
    private val channelCredit=Regex("(?i)\\s*[|｜]\\s*STUDIO CHOOM(?: ORIGINAL)?$")
    private val lim=norm("임재현")
    private val love=norm("사랑에 연습이 있었다면")
    private val green=norm("GReeeeN")
    private val kiseki=setOf(norm("キセキ"),norm("키세키"),"kiseki")
    private val artists=mapOf(
        "JENNIE" to listOf("제니","Gennie"),
        "G-DRAGON" to listOf("지드래곤"),
        "Yuuri" to listOf("優里","유우리"),
        "임재현" to listOf("Jaehyun Lim","Lim Jae Hyun"),
        "IU" to listOf("아이유"),
        "TAEYEON" to listOf("태연"),
        "ILLIT" to listOf("아일릿"),
        "IVE" to listOf("아이브"),
        "RESCENE" to listOf("리센느"),
        "DAVICHI" to listOf("다비치"),
        "Sakurazaka46" to listOf("櫻坂46")
    ).flatMap { (name, aliases) -> (aliases+name).map { norm(it) to name } }.toMap()

    fun artistName(value:String):String {
        val name=producer.replace(value,"").trim().replace(suffix,"")
        return artists[norm(name)] ?: name
    }
    fun artistKey(value:String)=norm(artistName(value))

    private fun clean(value:String):String {
        val head=value.substringBeforeLast("//",value)
        val text=if(head!=value && badge.matches(value.substringAfterLast("//").trim()))head else value
        return brackets.replace(channelCredit.replace(text,"")) { if(badge.matches(it.groupValues[1].trim()))" " else it.value }
            .replace(Regex("\\s+")," ").trim()
    }

    private fun songTitle(value:String)=featuring.replace(producer.replace(clean(value),""),"").trim()
    fun titleKey(title:String,artist:String):String {
        val text=songTitle(title)
        val key=norm(text)
        return when (artistKey(artist)) {
            lim -> if(key=="iftherewaspracticeinlove")love else key
            green -> if(norm(text.substringBefore('(')) in kiseki &&
                (text.indexOf('(')<0 || text.substringAfter('(').substringBeforeLast(')').split(',').all {
                    norm(it) in kiseki
                }))"kiseki" else key
            else -> key
        }
    }

    private fun parts(video:YoutubeVideo):Pair<String,String> {
        val text=clean(video.title)
        val separator=split.find(text)
        if(separator!=null) {
            val prefix=text.substring(0,separator.range.first).trim()
            val artist=artistName(prefix.substringAfterLast(": "))
            if(artist.length in 2..60) return text.substring(separator.range.last+1).trim() to artist
        }
        quotedTitle.matchEntire(text)?.let {quoted->
            val credit=quoted.groupValues[1].trim()
            val aliases=creditedAlias.matchEntire(credit)
            val artist=if(aliases!=null && artistKey(aliases.groupValues[1])==artistKey(aliases.groupValues[2]))
                artistName(aliases.groupValues[1]) else artistName(credit)
            if(artist.length in 2..60)return quoted.groupValues[2] to artist
        }
        val channel=video.channel.trim()
        return text to (artists[norm(channel)] ?: artistName(channel).takeIf { suffix.containsMatchIn(channel) }.orEmpty())
    }

    fun credits(video:YoutubeVideo, verifiedTitle:String="", verifiedArtist:String=""):Credits {
        val (title, artist)=parts(video)
        val name=artistName(verifiedArtist.ifBlank {artist})
        val preferred=when {
            artistKey(name)==lim && titleKey(verifiedTitle.ifBlank {title},name)==love ->
                "사랑에 연습이 있었다면"
            else -> songTitle(verifiedTitle.ifBlank {title})
        }
        val production=(producer.find(verifiedTitle) ?: producer.find(video.title))?.groupValues?.get(1)?.trim()
        return Credits(preferred,if(name.isNotBlank() && !production.isNullOrBlank())"$name (Prod. $production)" else name)
    }
    private val alternative=Regex("(?i)\\b(cover|karaoke|remix|sped|slowed|live|concert|instrumental)\\b|커버|라이브|콘서트|歌ってみた")
    fun usesVideoArtwork(video:YoutubeVideo)=alternative.containsMatchIn(video.title)
    fun recent(videos:List<YoutubeVideo>,history:List<String>,signals:Map<String,ListeningSignal>):List<YoutubeVideo> =
        videos.sortedWith(compareBy<YoutubeVideo> {history.indexOf("youtube:${it.videoId}").takeIf {index->index>=0} ?: 1000}
            .thenByDescending {signals["youtube:${it.videoId}"]?.lastPlayed ?: 0L})
    fun query(video:YoutubeVideo):String {
        val (title,artist)=parts(video)
        if(artistKey(artist)==green && titleKey(title,artist)=="kiseki")return "GReeeeN キセキ"
        if(artistKey(artist)==lim && titleKey(title,artist)==love)return "Jaehyun Lim If there was practice in love"
        return (artist+" "+songTitle(title)).trim().take(150)
    }
    fun choose(video:YoutubeVideo,candidates:List<OdifyMusicIdentity>):OdifyMusicIdentity? {
        if(usesVideoArtwork(video))return null
        val (title,artist)=parts(video)
        val expectedArtist=artistKey(artist)
        val channel=artistKey(video.channel)
        return candidates.filter {song ->
            val songArtist=artistKey(song.artist)
            val name=titleKey(song.title,song.artist)
            val uploadTitle=titleKey(title,song.artist)
            name.length>=2 && songArtist.isNotEmpty() &&
                if(expectedArtist.isNotEmpty()) expectedArtist==songArtist && uploadTitle==name
                else uploadTitle==songArtist+name || channel==songArtist && uploadTitle==name
        }.sortedBy {it.compilation}.distinctBy {it.artistId.ifBlank {artistKey(it.artist)} to titleKey(it.title,it.artist)}.singleOrNull()
    }
    fun artistAlias(name:String,videos:List<YoutubeVideo>):String? {
        val pattern=Regex(Regex.escape(name)+"\\s*\\(([A-Za-z][A-Za-z0-9 ._-]{1,35})\\)",RegexOption.IGNORE_CASE)
        val reverse=Regex("([A-Za-z][A-Za-z0-9._-]{1,35})\\s*\\("+Regex.escape(name)+"\\)",RegexOption.IGNORE_CASE)
        val excluded=setOf("live","official","mv","audio","lyrics","4k","hd","fancam")
        val counts=videos.mapNotNull {(pattern.find(it.title) ?: reverse.find(it.title))?.groupValues?.get(1)?.trim()}
            .filter {norm(it) !in excluded}.groupingBy {it.uppercase(Locale.ROOT)}.eachCount()
        return counts.entries.sortedByDescending {it.value}.firstOrNull {entry ->entry.value>=2 &&
            videos.any {norm(it.channel.replace(Regex("(?i)(?:\\s*-\\s*Topic|Official)$"),""))==norm(entry.key)}}?.key
    }
}

internal object OdifyCatalogHttp {
    fun get(address:String,limit:Int=2_000_000):String {
        val url=URL(address)
        require(url.protocol=="https" && url.host in setOf("itunes.apple.com","music.apple.com"))
        val connection=url.openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout=8_000;connection.readTimeout=12_000;connection.instanceFollowRedirects=false
            connection.setRequestProperty("User-Agent","Odify/1.0 Music Metadata")
            check(connection.responseCode==200) {"음악 정보에 연결하지 못했어요 (${connection.responseCode})"}
            connection.inputStream.use {input ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) {val n=input.read(buffer);if(n<0)break;check(out.size()+n<=limit);out.write(buffer,0,n)}
                out.toString("UTF-8")
            }
        } finally {connection.disconnect()}
    }
    fun parse(row:JSONObject):OdifyMusicIdentity? {
        val id=row.optString("trackId");val title=row.optString("trackName");val artist=row.optString("artistName")
        if(row.optString("kind")!="song" || !id.matches(Regex("[0-9]+")) || title.isBlank() || artist.isBlank())return null
        return OdifyMusicIdentity(id,title,artist,row.optString("collectionName"),row.optString("artworkUrl100"),
            row.optString("artistId"),row.optString("artistViewUrl"),row.optString("trackViewUrl"),row.optLong("trackTimeMillis"),row.optString("primaryGenreName"),
            albumUrl=row.optString("collectionViewUrl"),
            compilation=row.optString("collectionArtistName").equals("Various Artists",true))
    }
    fun songs(raw:String):List<OdifyMusicIdentity> {
        val rows=JSONObject(raw).getJSONArray("results")
        return (0 until minOf(rows.length(),200)).mapNotNull {rows.optJSONObject(it)?.let(::parse)}
    }
}

internal class OdifyMusicCatalog(context:Context) {
    private val app=context.applicationContext
    private val directory=File(app.cacheDir,"recording_metadata").apply {mkdirs()}
    suspend fun recording(artist:String,title:String,id:String=""):OdifyMusicIdentity? = withContext(Dispatchers.IO) {
        val query="$artist $title".trim().take(150)
        val key=if(id.matches(Regex("[0-9]+")))"id-$id" else "q-"+OdifyMusicMatching.cacheKey(query)
        val file=File(directory,"$key.json")
        val raw=if(file.isFile && System.currentTimeMillis()-file.lastModified()<24*60*60_000L)file.readText() else {
            val address=if(id.matches(Regex("[0-9]+")))"https://itunes.apple.com/lookup?id=$id&country=us&entity=song"
                else "https://itunes.apple.com/search?term=${URLEncoder.encode(query,"UTF-8")}&country=us&entity=song&limit=25"
            OdifyCatalogHttp.get(address).also {file.writeText(it)}
        }
        val candidates=OdifyCatalogHttp.songs(raw)
        if(id.matches(Regex("[0-9]+")))candidates.firstOrNull {it.id==id}
        else candidates.sortedBy {it.compilation}.distinctBy {OdifyMusicMatching.artistKey(it.artist) to OdifyMusicMatching.titleKey(it.title,it.artist)}.singleOrNull {
            OdifyMusicMatching.artistKey(it.artist)==OdifyMusicMatching.artistKey(artist) &&
                OdifyMusicMatching.titleKey(it.title,it.artist)==OdifyMusicMatching.titleKey(title,artist)
        }
    }
    suspend fun video(video:YoutubeVideo):OdifyMusicIdentity? = withContext(Dispatchers.IO) {
        if(OdifyMusicMatching.usesVideoArtwork(video))return@withContext null
        val query=OdifyMusicMatching.query(video)
        if(query.length<2)return@withContext null
        val country=when {
            query.any {it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff'} -> "jp"
            query.any {it in '\uac00'..'\ud7a3'} -> "kr"
            else -> "us"
        }
        for (storefront in listOf(country,"us").distinct()) {
            val key=OdifyMusicMatching.cacheKey(query)
            val file=File(directory,"video-$storefront-$key.json")
            val raw=if(file.isFile && System.currentTimeMillis()-file.lastModified()<24*60*60_000L)file.readText() else
                OdifyCatalogHttp.get("https://itunes.apple.com/search?term="+URLEncoder.encode(query,"UTF-8")+"&country=$storefront&media=music&entity=song&limit=25").also {file.writeText(it)}
            OdifyMusicMatching.choose(video,OdifyCatalogHttp.songs(raw))?.let {return@withContext it}
        }
        null
    }
    suspend fun cover(identity:OdifyMusicIdentity):String?=OdifyCatalogArt.download(app,identity.artwork,
        "album-${identity.id}-"+OdifyMusicMatching.cacheKey(identity.artwork).take(12),600)
    suspend fun enrich(store:OdifyStreamingLibrary,video:YoutubeVideo,identity:OdifyMusicIdentity?=null):Boolean {
        if(OdifyMusicMatching.usesVideoArtwork(video))return false
        val music=identity ?: store.identity(video.videoId) ?: video(video) ?: return false
        // Publish the verified text immediately, without substituting a video thumbnail.
        store.presentation(video.videoId,music,null)
        cover(music)?.let {store.presentation(video.videoId,music,it)}
        return true
    }
}

internal object OdifyArtistPortrait {
    fun address(html:String):String? = Regex("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)",RegexOption.IGNORE_CASE)
        .find(html)?.groupValues?.get(1)?.replace("&amp;","&")?.takeIf {address ->
            runCatching {URL(address).let {it.protocol=="https" && it.host.endsWith(".mzstatic.com") &&
                Regex("/(AMCArtistImages|Features)[0-9]+/").containsMatchIn(it.path)}}.getOrDefault(false)
        }
}

internal object OdifyCatalogArt {
    suspend fun download(context:Context,address:String,key:String,pixels:Int=400):String?=withContext(Dispatchers.IO) {
        runCatching {
            val uri=URL(address)
            require(uri.protocol=="https" && uri.host.endsWith(".mzstatic.com"))
            require(Regex("[A-Za-z0-9_-]{1,160}").matches(key))
            val directory=File(context.cacheDir,"official_artwork").apply {mkdirs()}
            val file=File(directory,"$key-$pixels.jpg")
            if(file.isFile)return@runCatching file.absolutePath
            val resized=address.replace(Regex("/[0-9]+x[0-9]+[a-z]*\\.(jpg|png|webp)$"),"/${pixels}x${pixels}bb.jpg")
            val conn=URL(resized).openConnection() as HttpURLConnection
            val bytes=try {
                conn.connectTimeout=8_000;conn.readTimeout=10_000;conn.instanceFollowRedirects=false
                check(conn.responseCode==200)
                conn.inputStream.use {input ->
                    val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192)
                    while(true){val n=input.read(buf);if(n<0)break;check(out.size()+n<=2_000_000);out.write(buf,0,n)};out.toByteArray()
                }
            } finally {conn.disconnect()}
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
            var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>pixels*2)sample*=2
            val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample}) ?: error("Invalid cover")
            val atomic=android.util.AtomicFile(file);val output=atomic.startWrite()
            try {check(bitmap.compress(Bitmap.CompressFormat.JPEG,92,output));atomic.finishWrite(output)}
            catch(error:Exception){atomic.failWrite(output);throw error}finally {bitmap.recycle()}
            file.absolutePath
        }.getOrElse {if(it is CancellationException)throw it;null}
    }
}
