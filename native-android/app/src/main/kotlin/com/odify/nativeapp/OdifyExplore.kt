package com.odify.nativeapp

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import androidx.compose.foundation.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ExploreTrack(val id:String,val title:String,val artist:String,val artwork:String,val genres:List<String>,val rank:Int)
data class ExploreChart(val country:String,val updated:String,val fetchedAt:Long,val tracks:List<ExploreTrack>,val cached:Boolean=false)

/** Public chart metadata only; never substitutes a chart result for an owned audio file. */
class OdifyExploreRepository(private val context:Context) {
    private fun parse(raw:String,country:String,fetchedAt:Long,cached:Boolean):ExploreChart {
        val feed=JSONObject(raw).getJSONObject("feed")
        val array=feed.getJSONArray("results")
        val tracks=(0 until minOf(array.length(),50)).mapNotNull {i ->
            val item=array.getJSONObject(i)
            val id=item.optString("id");val title=item.optString("name");val artist=item.optString("artistName")
            if(id.isBlank() || title.isBlank() || artist.isBlank()) return@mapNotNull null
            val genres=item.optJSONArray("genres")
            val labels=(0 until (genres?.length() ?: 0)).mapNotNull {j -> genres?.optJSONObject(j)}
                .filter {it.optString("genreId")!="34"}.map {it.optString("name")}.filter(String::isNotBlank)
            ExploreTrack(id,title,artist,item.optString("artworkUrl100"),labels,i+1)
        }.distinctBy {it.id}
        require(tracks.isNotEmpty()) {"차트가 비어 있습니다."}
        return ExploreChart(country,feed.optString("updated"),fetchedAt,tracks,cached)
    }

    suspend fun load(country:String,force:Boolean=false):ExploreChart = withContext(Dispatchers.IO) {
        require(country in setOf("kr","jp","us"))
        val file=File(context.cacheDir,"explore_$country.json")
        val existing=runCatching {parse(file.readText(),country,file.lastModified(),true)}.getOrNull()
        if(!force && existing!=null && System.currentTimeMillis()-existing.fetchedAt < 6L*60*60*1000) return@withContext existing
        try {
            val connection=URL("https://rss.applemarketingtools.com/api/v2/$country/music/most-played/50/songs.json").openConnection() as HttpURLConnection
            val raw=try {
                connection.connectTimeout=12_000;connection.readTimeout=20_000
                connection.setRequestProperty("User-Agent","Odify/1.0 (public music chart metadata)")
                check(connection.responseCode==200) {"차트 연결 실패 (${connection.responseCode})"}
                connection.inputStream.use { input ->
                    val bytes=input.readBytesBounded(2_000_000)
                    String(bytes,Charsets.UTF_8)
                }
            } finally {connection.disconnect()}
            val result=parse(raw,country,System.currentTimeMillis(),false)
            val atomic=AtomicFile(file)
            val output=atomic.startWrite()
            try {output.write(raw.toByteArray(Charsets.UTF_8));atomic.finishWrite(output)}
            catch(failure:Exception) {atomic.failWrite(output);throw failure}
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
    val cover by produceState<ImageBitmap?>(null,track.artwork) {
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
                BitmapFactory.decodeByteArray(bytes,0,bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(13.dp)).background(OdifyPalette.surface),contentAlignment=Alignment.Center) {
        cover?.let {Image(it,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}
            ?: Icon(Icons.Outlined.MusicNote,null,tint=Color(0xFF999CA2))
    }
}

@Composable
fun OdifyExplore(onProfile:()->Unit,onSearch:(String)->Unit) {
    val context=LocalContext.current
    val repository=remember {OdifyExploreRepository(context.applicationContext)}
    var country by rememberSaveable {mutableStateOf("kr")}
    var genre by rememberSaveable {mutableStateOf("")}
    var refresh by remember {mutableIntStateOf(0)}
    var chart by remember {mutableStateOf<ExploreChart?>(null)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(country,refresh) {
        busy=true;error=null;chart=null;genre=""
        try {chart=repository.load(country,refresh>0)}
        catch(cancel:CancellationException) {throw cancel}
        catch(_:Exception) {error="차트를 불러오지 못했어요. 연결을 확인하고 다시 시도해 주세요."}
        finally {busy=false}
    }
    val visible=chart?.tracks.orEmpty().filter {genre.isEmpty() || genre in it.genres}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(22.dp,14.dp,22.dp,160.dp)) {
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Explore",fontSize=29.sp,fontWeight=FontWeight.Bold,color=OdifyPalette.text,letterSpacing=(-.8).sp)
                    Text("A world of good music.",fontSize=12.sp,color=OdifyPalette.muted)
                }
                IconButton(onClick=onProfile) {Icon(Icons.Outlined.PersonOutline,"프로필 및 설정")}
            }
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                listOf("kr" to "한국","jp" to "일본","us" to "미국").forEach {(code,label) ->
                    FilterChip(selected=country==code,onClick={country=code},label={Text(label)},
                        leadingIcon={if(country==code) Icon(Icons.Outlined.Check,null,Modifier.size(15.dp))})
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick={refresh++},enabled=!busy) {Icon(Icons.Outlined.Refresh,"차트 새로고침")}
            }
            Spacer(Modifier.height(15.dp))
            Text("Hot right now",fontSize=26.sp,fontWeight=FontWeight.Bold,color=OdifyPalette.text)
            Text("Apple Music · ${country.uppercase()} 인기곡 Top 50",fontSize=12.sp,color=OdifyPalette.muted,modifier=Modifier.padding(top=5.dp))
            chart?.let {data ->
                val updated=runCatching {java.time.ZonedDateTime.parse(data.updated,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM.dd HH:mm"))}.getOrDefault("")
                Text("$updated 갱신"+(if(data.cached) " · 저장된 차트" else ""),fontSize=10.sp,color=Color(0xFF999CA2),modifier=Modifier.padding(top=3.dp))
                Spacer(Modifier.height(15.dp))
                LazyRow(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                    items(listOf("")+data.tracks.flatMap {it.genres}.distinct().take(16)) {tag ->
                        FilterChip(selected=genre==tag,onClick={genre=tag},label={Text(tag.ifBlank {"전체"},fontSize=11.sp)})
                    }
                }
                if(genre.isNotEmpty()) Text("국가 Top 50 안에서 장르별로 모았어요",fontSize=10.sp,color=OdifyPalette.muted)
                Spacer(Modifier.height(13.dp))
            }
        }
        if(busy) item {LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical=24.dp))}
        error?.let {message -> item {Text(message,fontSize=13.sp,color=OdifyPalette.muted,modifier=Modifier.padding(vertical=20.dp));
            TextButton(onClick={refresh++}) {Text("다시 불러오기")}}}
        items(visible,key={it.id}) {track ->
            Row(Modifier.fillMaxWidth().clickable {onSearch(track.title)}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(track.rank.toString().padStart(2,'0'),fontSize=13.sp,color=Color(0xFF999CA2),modifier=Modifier.width(30.dp))
                ExploreCover(track,Modifier.size(55.dp))
                Column(Modifier.weight(1f).padding(start=13.dp,end=6.dp)) {
                    Text(track.title,fontSize=14.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(track.artist,fontSize=11.sp,color=OdifyPalette.muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(track.genres.firstOrNull().orEmpty(),fontSize=9.sp,color=Color(0xFFA0A2A8),maxLines=1)
                }
                Icon(Icons.Outlined.Search,"내 음악에서 ${track.title} 찾기",tint=Color(0xFF8B8E96),modifier=Modifier.size(20.dp))
            }
        }
        if(visible.isNotEmpty()) item {
            Text("차트는 새로운 음악을 발견하는 곳이에요. 곡을 누르면 내 음악에서 검색합니다.",
                fontSize=11.sp,color=OdifyPalette.muted,modifier=Modifier.padding(top=20.dp))
        }
    }
}
