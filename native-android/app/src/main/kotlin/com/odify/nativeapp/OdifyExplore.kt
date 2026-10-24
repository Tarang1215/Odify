package com.odify.nativeapp

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

data class ExploreTrack(val id:String,val title:String,val artist:String,val artwork:String,val genres:List<String>,val rank:Int)
data class ExploreChart(val country:String,val updated:String,val fetchedAt:Long,val tracks:List<ExploreTrack>,val cached:Boolean=false)

/** Public chart metadata. Playback always resolves to an owned, identified file. */
class OdifyExploreRepository(private val context:Context) {
    private val memory=java.util.concurrent.ConcurrentHashMap<String,ExploreChart>()
    fun peek(country:String):ExploreChart?=memory[country]
    suspend fun cached(country:String):ExploreChart?=withContext(Dispatchers.IO) {
        require(country in setOf("kr","jp","us"))
        memory[country] ?: run {
            val file=File(context.cacheDir,"explore100_$country.json")
            val saved=runCatching {parse(file.readText(),country,file.lastModified(),true)}.getOrNull()
            val seed=saved ?: runCatching {parse(context.assets.open("charts/$country.json").bufferedReader().use {it.readText()},country,0,true)}.getOrNull()
            seed?.also {memory[country]=it}
        }
    }
    private fun parse(raw:String,country:String,fetchedAt:Long,cached:Boolean):ExploreChart {
        val feed=JSONObject(raw).getJSONObject("feed")
        val array=feed.getJSONArray("results")
        val tracks=(0 until minOf(array.length(),100)).mapNotNull {i ->
            val item=array.getJSONObject(i)
            val id=item.optString("id");val title=item.optString("name");val artist=item.optString("artistName")
            if(id.isBlank() || title.isBlank() || artist.isBlank()) return@mapNotNull null
            val genres=item.optJSONArray("genres")
            val labels=(0 until (genres?.length() ?: 0)).mapNotNull {j -> genres?.optJSONObject(j)}
                .filter {it.optString("genreId")!="34"}.map {it.optString("name")}.filter(String::isNotBlank)
            ExploreTrack(id,title,artist,item.optString("artworkUrl100"),labels,i+1)
        }.distinctBy {it.id}
        require(tracks.size==100) {"차트 100곡을 확인하지 못했습니다."}
        return ExploreChart(country,feed.optString("updated"),fetchedAt,tracks,cached)
    }
    private fun request(url:String,limit:Int):String {
        val conn=URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout=8_000;conn.readTimeout=12_000
            conn.setRequestProperty("User-Agent","Mozilla/5.0 Odify/1.0")
            check(conn.responseCode==200) {"Chart HTTP ${conn.responseCode}"}
            conn.inputStream.use {String(it.readBytesBounded(limit),Charsets.UTF_8)}
        } finally {conn.disconnect()}
    }
    private fun publicPlaylist(country:String,seed:ExploreChart?):String {
        val id=when(country) {"kr"->"pl.d3d10c32fbc540b38e266367dc8cb00c";"jp"->"pl.043a2c9876114d95a4659988497567be";else->"pl.606afcbb70264d2eb2b51d8dbcfa6a12"}
        val html=request("https://music.apple.com/$country/playlist/top-100/$id",3_000_000)
        val match=Regex("<script[^>]*id=\"serialized-server-data\"[^>]*>(.*?)</script>",RegexOption.DOT_MATCHES_ALL).find(html)
            ?: error("Public chart metadata missing")
        val root=JSONObject(match.groupValues[1]);val ranked=ArrayList<JSONObject>()
        fun visit(node:Any?) {
            when(node) {
                is JSONObject -> {
                    if(node.optJSONObject("contentDescriptor")?.optString("kind")=="song" &&
                        node.optString("rankingText").toIntOrNull()!=null && node.optString("artistName").isNotBlank()) ranked.add(node)
                    else node.keys().forEach {visit(node.opt(it))}
                }
                is org.json.JSONArray -> (0 until node.length()).forEach {visit(node.opt(it))}
            }
        }
        visit(root)
        val results=org.json.JSONArray()
        ranked.sortedBy {it.optString("rankingText").toInt()}.forEach {item ->
            val identity=item.getJSONObject("contentDescriptor").getJSONObject("identifiers").getString("storeAdamID")
            val artwork=item.optJSONObject("artwork")?.optJSONObject("dictionary")?.optString("url").orEmpty()
                .replace("{w}","200").replace("{h}","200").replace("{f}","jpg")
            results.put(JSONObject().put("id",identity).put("name",item.getString("title"))
                .put("artistName",item.getString("artistName")).put("artworkUrl100",artwork)
                .put("genres",org.json.JSONArray().apply {seed?.tracks?.firstOrNull {it.id==identity}?.genres?.forEach {put(JSONObject().put("name",it))}}))
        }
        return JSONObject().put("feed",JSONObject().put("updated",java.time.Instant.now().toString()).put("results",results)).toString()
    }
    suspend fun load(country:String,force:Boolean=false):ExploreChart = withContext(Dispatchers.IO) {
        require(country in setOf("kr","jp","us"))
        val file=File(context.cacheDir,"explore100_$country.json")
        val existing=cached(country)
        if(!force && existing!=null && System.currentTimeMillis()-existing.fetchedAt < 6L*60*60*1000) return@withContext existing
        try {
            val raw=if(country=="kr") publicPlaylist(country,existing) else try {
                request("https://rss.applemarketingtools.com/api/v2/$country/music/most-played/100/songs.json",2_000_000)
            } catch(cancel:CancellationException) {throw cancel}
            catch(_:Exception) {publicPlaylist(country,existing)}
            val result=parse(raw,country,System.currentTimeMillis(),false)
            val atomic=AtomicFile(file);val output=atomic.startWrite()
            try {output.write(raw.toByteArray(Charsets.UTF_8));atomic.finishWrite(output)}
            catch(failure:Exception) {atomic.failWrite(output);throw failure}
            memory[country]=result
            result
        } catch(cancel:CancellationException) {throw cancel}
        catch(failure:Exception) {existing ?: throw failure}
    }
}

private fun java.io.InputStream.readBytesBounded(limit:Int):ByteArray {
    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(16*1024)
    while(true) {val n=read(buffer);if(n<0)break;require(output.size()+n<=limit);output.write(buffer,0,n)}
    return output.toByteArray()
}

@Composable
private fun ExploreCover(track:ExploreTrack,modifier:Modifier) {
    val context=LocalContext.current
    val cover by produceState<ImageBitmap?>(ExploreArtworkMemory.peek(track.artwork),track.artwork) {
        if(value!=null)return@produceState
        value=withContext(Dispatchers.IO) {
            runCatching {
                val url=URL(track.artwork)
                require(url.protocol=="https" && url.host.endsWith(".mzstatic.com"))
                val key=java.security.MessageDigest.getInstance("SHA-256").digest(track.artwork.toByteArray()).take(12).joinToString("") {"%02x".format(it)}
                val file=File(context.cacheDir,"explore_art_$key.jpg")
                val bytes=if(file.exists()) file.readBytes() else {
                    val conn=url.openConnection() as HttpURLConnection
                    val data=try {conn.connectTimeout=8000;conn.readTimeout=8000
                        check(conn.responseCode==200);conn.inputStream.use {it.readBytesBounded(400_000)}
                    } finally {conn.disconnect()}
                    file.writeBytes(data);data
                }
                val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                require(options.outWidth in 1..2048 && options.outHeight in 1..2048)
                var sample=1
                while(options.outWidth/sample>256 || options.outHeight/sample>256)sample*=2
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample})
                    ?.asImageBitmap()?.also {ExploreArtworkMemory.put(track.artwork,it)}
            }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(13.dp)).background(OdifyPalette.surface),contentAlignment=Alignment.Center) {
        cover?.let {Image(it,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}
            ?: Icon(Icons.Outlined.MusicNote,null,tint=Color(0xFF999CA2))
    }
}

private object ExploreArtworkMemory {
    private val entries=object:LinkedHashMap<String,ImageBitmap>(48,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,ImageBitmap>?)=size>48
    }
    @Synchronized fun peek(key:String)=entries[key]
    @Synchronized fun put(key:String,value:ImageBitmap) {entries[key]=value}
}

@Composable
fun OdifyExplore(index:OdifyChartMatching.Index,repository:OdifyExploreRepository,onProfile:()->Unit,onPlay:(List<OdifySong>,Int)->Unit,
    onMissingTrack:(ExploreTrack)->Unit,onSaveChart:(String,List<String>)->Unit,onSync:()->Unit,onAddTrack:(ExploreTrack,OdifySong?)->Unit) {
    val context=LocalContext.current
    var country by rememberSaveable {mutableStateOf("kr")}
    var genre by rememberSaveable {mutableStateOf("")}
    var refresh by remember {mutableIntStateOf(0)}
    var handledRefresh by remember {mutableIntStateOf(0)}
    val listState=rememberLazyListState()
    var chart by remember {mutableStateOf(repository.peek(country))}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(country,refresh) {
        val force=refresh>handledRefresh;handledRefresh=refresh
        busy=true;error=null;chart=repository.peek(country);genre=""
        if(chart==null) chart=repository.cached(country)
        try {chart=repository.load(country,force)}
        catch(cancel:CancellationException) {throw cancel}
        catch(_:Exception) {error="차트를 불러오지 못했어요. 연결을 확인하고 다시 시도해 주세요."}
        finally {busy=false}
    }
    LaunchedEffect(country) {listState.scrollToItem(0)}
    val visible=chart?.tracks.orEmpty().filter {genre.isEmpty() || genre in it.genres}
    val matched=remember(chart,index) {chart?.tracks.orEmpty().associate {it.id to index.resolve(it)}}
    val ready=visible.mapNotNull {matched[it.id]}.distinctBy {it.contentMd5.ifBlank {it.id}}
    val countryName=when(country) {"kr"->"한국";"jp"->"일본";else->"미국"}
    fun play(shuffle:Boolean=false) {if(ready.isNotEmpty()) onPlay(if(shuffle) ready.shuffled() else ready,0)}
    LazyColumn(Modifier.fillMaxSize().pointerInput(country) {
        awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=false)
            var childOwns=false;var target:String?=null
            while(true) {
                // Children handle horizontal genre chips before this pass;
                // consume country swipes before the outer primary-tab observer.
                val pointer=awaitPointerEvent(PointerEventPass.Main).changes.firstOrNull {it.id==down.id} ?: break
                val dx=pointer.position.x-down.position.x;val dy=pointer.position.y-down.position.y
                if(pointer.isConsumed) childOwns=true
                if(!childOwns && abs(dx)>56.dp.toPx() && abs(dx)>abs(dy)*1.6f) {
                    val countries=listOf("kr","jp","us")
                    target=countries.getOrNull(countries.indexOf(country)+if(dx<0)1 else -1) ?: country
                    pointer.consume()
                }
                if(!pointer.pressed) {target?.takeIf {!childOwns}?.let {country=it};break}
            }
        }
    },state=listState,contentPadding=PaddingValues(22.dp,14.dp,22.dp,160.dp)) {
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Explore",fontSize=29.sp,fontWeight=FontWeight.Bold,color=OdifyPalette.text,letterSpacing=(-.8).sp)
                    Text("A world of good music.",fontSize=12.sp,color=OdifyPalette.muted)
                }
                IconButton(onClick=onProfile) {Icon(Icons.Outlined.PersonOutline,"프로필 및 설정")}
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically) {
                listOf("kr" to "한국","jp" to "일본","us" to "미국").forEach {(code,label) ->
                    OdifyPill(label,country==code) {country=code}
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick={refresh++;onSync()},enabled=!busy) {Icon(Icons.Outlined.Refresh,"차트와 음악 새로고침")}
            }
            Spacer(Modifier.height(14.dp))
            Text("$countryName Top 100",fontSize=26.sp,fontWeight=FontWeight.Bold,color=OdifyPalette.text)
            chart?.let {data ->
                val timestamp=runCatching {java.time.Instant.parse(data.updated)}.getOrElse {
                    runCatching {java.time.ZonedDateTime.parse(data.updated,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()}
                        .getOrDefault(java.time.Instant.ofEpochMilli(data.fetchedAt))}
                val updated=timestamp.atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM.dd HH:mm"))
                Text("$updated 갱신"+(if(data.cached) " · 저장된 차트" else ""),fontSize=10.sp,color=OdifyPalette.muted,modifier=Modifier.padding(top=4.dp))
                Text("${ready.size}/${visible.size}곡 재생 준비 · 동기화하면 나머지도 이어집니다",fontSize=11.sp,color=OdifyPalette.muted,
                    modifier=Modifier.padding(top=9.dp,bottom=10.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    FilledIconButton(onClick={play()},enabled=ready.isNotEmpty(),colors=IconButtonDefaults.filledIconButtonColors(
                        containerColor=OdifyPalette.text,contentColor=OdifyPalette.background)) {Icon(Icons.Outlined.PlayArrow,"Top 100 재생")}
                    IconButton(onClick={play(true)},enabled=ready.isNotEmpty()) {Icon(Icons.Outlined.Shuffle,"Top 100 랜덤 재생")}
                    TextButton(onClick={onSaveChart("$countryName Top 100",data.tracks.map {track -> matched[track.id]?.id ?: "chart:${track.id}"})}) {
                        Icon(Icons.Outlined.PlaylistAdd,null,Modifier.size(18.dp));Spacer(Modifier.width(5.dp));Text("플레이리스트 저장",fontSize=11.sp)
                    }
                }
                Spacer(Modifier.height(9.dp))
                LazyRow(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                    items(listOf("")+data.tracks.flatMap {it.genres}.distinct().take(16)) {tag ->
                        OdifyPill(tag.ifBlank {"전체"},genre==tag) {genre=tag}
                    }
                }
                if(genre.isNotEmpty()) Text("국가 Top 100 안에서 장르별로 모았어요",fontSize=10.sp,color=OdifyPalette.muted)
                Spacer(Modifier.height(13.dp))
            }
        }
        if(busy) item {LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical=24.dp))}
        error?.let {message -> item {Text(message,fontSize=13.sp,color=OdifyPalette.muted,modifier=Modifier.padding(vertical=20.dp))}}
        items(visible,key={it.id}) {track ->
            val song=matched[track.id]
            Row(Modifier.fillMaxWidth().clickable {
                if(song!=null) onPlay(ready,ready.indexOfFirst {it.id==song.id}.coerceAtLeast(0))
                else onMissingTrack(track)
            }.padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(track.rank.toString().padStart(2,'0'),fontSize=12.sp,color=OdifyPalette.muted,modifier=Modifier.width(30.dp))
                ExploreCover(track,Modifier.size(52.dp))
                Column(Modifier.weight(1f).padding(start=12.dp,end=6.dp)) {
                    Text(track.title,fontSize=13.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis,color=OdifyPalette.text)
                    Text(track.artist,fontSize=10.sp,color=OdifyPalette.muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                IconButton(onClick={onAddTrack(track,song)},modifier=Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.PlaylistAdd,"${track.title} 플레이리스트에 추가",
                        tint=OdifyPalette.text,modifier=Modifier.size(21.dp))
                }
            }
        }
    }
}
