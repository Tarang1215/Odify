package com.odify.nativeapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val browseGenres=listOf(
    OdifyMood("k-pop","K-Pop","",listOf("K-Pop","케이팝","Korean Pop","Korean","K-pop/댄스")),
    OdifyMood("j-pop","J-Pop","",listOf("J-Pop","제이팝","Japanese Pop")),
    OdifyMood("pop","Pop","",listOf("Pop","팝","ポップ")),
    OdifyMood("rock","Rock","",listOf("Rock","록","락","ロック","Alternative Rock","Indie Rock")),
    OdifyMood("hip-hop","Hip-Hop","",listOf("Hip-Hop","Hip-Hop/Rap","힙합","랩","ヒップホップ／ラップ")),
    OdifyMood("rnb","R&B","",listOf("R&B","R&B/Soul","알앤비","소울","R&B／ソウル")),
    OdifyMood("jazz","Jazz","",listOf("Jazz","재즈","ジャズ","Smooth Jazz")),
    OdifyMood("classical","Classical","",listOf("Classical","클래식","クラシック","Classical Crossover"))
)
private val genreColors=listOf(0xFF65415E,0xFF325557,0xFF85434B,0xFF69452B,0xFF434267,0xFF68394D,0xFF3C5550,0xFF575167)

internal fun genreMatches(genre:OdifyMood,tags:List<String>):Boolean {
    val names=genre.genres.map(OdifyMusicMatching::norm).toSet()
    return tags.any {tag-> tag.split(" / ",",",";").any {OdifyMusicMatching.norm(it) in names}}
}

@Composable internal fun OdifyLibraryOverview(modes:Map<String,Pair<String,ImageVector>>,onSelect:(String)->Unit,
    onYoutube:()->Unit,playlists:List<OdifyPlaylist>,songs:List<OdifySong>,onPlaylist:(OdifyPlaylist)->Unit,
    onPlay:(List<OdifySong>,Int)->Unit) {
    // The import and playlist stores append new entries. Synced and indexed sources lack addition dates.
    val imports=remember(songs){songs.asReversed().filter {
        it.id.matches(Regex("[0-9a-f]{64}")) || it.id.startsWith("offline:") || it.id.startsWith("drive-")
    }.take(4)}
    val recent=remember(playlists){playlists.takeLast(4).asReversed()}
    val thumbnails by produceState(emptyMap<String,OdifySong?>(),recent,songs) {
        value=withContext(Dispatchers.Default) {
            val byId=songs.associateBy {it.id}
            recent.associate {list->list.id to list.coverSong(list.songIds.mapNotNull(byId::get))}
        }
    }
    LazyVerticalGrid(columns=GridCells.Fixed(2),contentPadding=PaddingValues(bottom=160.dp),
        horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        item(span={GridItemSpan(maxLineSpan)}) {
            Column {
                modes.forEach { (key,entry)->OdifyBrowseRow(entry.first,entry.second){onSelect(key)} }
                OdifyBrowseRow("YouTube 플레이리스트",Icons.Outlined.PlaylistPlay,onYoutube)
            }
        }
        if(recent.isNotEmpty() || imports.isNotEmpty())item(span={GridItemSpan(maxLineSpan)}) {
            Text("최근에 추가한 항목",fontSize=20.sp,fontWeight=FontWeight.Bold,color=OdifyPalette.text,
                modifier=Modifier.padding(top=24.dp,bottom=12.dp))
        }
        items(recent,key={"playlist:"+it.id}) {list->
            LibraryCoverTile(thumbnails[list.id],list.name,"${list.songIds.size}곡"){onPlaylist(list)}
        }
        items(imports,key={"import:"+it.id}) {song->
            LibraryCoverTile(song,song.title,song.artist){onPlay(imports,imports.indexOf(song))}
        }
    }
}

@Composable private fun LibraryCoverTile(song:OdifySong?,title:String,subtitle:String,onClick:()->Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick=onClick).padding(bottom=16.dp)) {
        AlbumArt(song,Modifier.fillMaxWidth().aspectRatio(1f),RoundedCornerShape(10.dp),fallbackKey=title.hashCode())
        Text(title,fontSize=13.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis,
            color=OdifyPalette.text,modifier=Modifier.padding(top=8.dp))
        Text(subtitle,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=OdifyPalette.muted)
    }
}

@Composable internal fun OdifyBrowseRow(label:String,icon:ImageVector,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=52.dp).clickable(onClick=onClick).padding(horizontal=4.dp),
        verticalAlignment=Alignment.CenterVertically) {
        Icon(icon,null,tint=OdifyPalette.text,modifier=Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label,Modifier.weight(1f),fontSize=15.sp,fontWeight=FontWeight.Medium,color=OdifyPalette.text)
        Icon(Icons.Outlined.ChevronRight,null,tint=OdifyPalette.muted,modifier=Modifier.size(18.dp))
    }
    HorizontalDivider(color=OdifyPalette.text.copy(alpha=.08f))
}

@Composable internal fun OdifyGenreBrowser(selected:String,onSelect:(String)->Unit,active:Boolean,songs:List<OdifySong>,
    discovery:List<ExploreTrack>,recommendations:List<OdifyRecommendation>,load:suspend(OdifyMood)->List<ExploreTrack>,
    index:OdifyChartMatching.Index,onPlay:(List<OdifySong>,Int)->Unit,onOnline:(ExploreTrack)->Unit,
    onOptions:(OdifySong)->Unit) {
    val genre=browseGenres.firstOrNull {it.key==selected}
    BackHandler(active && genre!=null) {onSelect("All")}
    if(genre==null) {
        Text("장르 둘러보기",fontSize=20.sp,fontWeight=FontWeight.Bold,color=OdifyPalette.text,
            modifier=Modifier.padding(top=6.dp,bottom=12.dp))
        val cardArt=remember(discovery) {browseGenres.associateWith {item->
            discovery.firstOrNull {it.artwork.isNotBlank() && genreMatches(item,it.genres)}
        }}
        val ownedArt by produceState(emptyMap<OdifyMood,OdifySong>(),songs) {
            value=withContext(Dispatchers.Default) {
                browseGenres.mapNotNull {item->songs.firstOrNull {
                    !it.artworkPath.isNullOrBlank() && genreMatches(item,listOf(it.genre))
                }?.let {item to it}}.toMap()
            }
        }
        LazyVerticalGrid(columns=GridCells.Fixed(2),contentPadding=PaddingValues(bottom=160.dp),
            horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(browseGenres,key={it.key}) {item->
                Box(Modifier.fillMaxWidth().height(108.dp).clip(RoundedCornerShape(16.dp))
                    .background(Color(genreColors[browseGenres.indexOf(item)])).clickable {onSelect(item.key)}) {
                    val art=Modifier.align(Alignment.BottomEnd).offset(x=10.dp,y=12.dp).size(76.dp).rotate(14f)
                    cardArt[item]?.let {ExploreCover(it,art)}
                        ?: ownedArt[item]?.let {AlbumArt(it,art,RoundedCornerShape(8.dp))}
                    Text(item.name,Modifier.padding(16.dp),fontSize=17.sp,fontWeight=FontWeight.Bold,color=Color.White)
                }
            }
        }
        return
    }
    val latestLoad by rememberUpdatedState(load)
    val online=remember(genre){mutableStateOf<List<ExploreTrack>>(emptyList())}
    val loading=remember(genre){mutableStateOf(true)}
    LaunchedEffect(genre,active) {
        if(!active)return@LaunchedEffect
        try {online.value=latestLoad(genre)}
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){ }
        finally {loading.value=false}
    }
    val results by produceState(emptyList<OdifyRecommendation>(),genre,online.value,recommendations,discovery,songs,index) {
        value=withContext(Dispatchers.Default) {
            val local=OdifySongPresentation.visible(songs.filter {genreMatches(genre,listOf(it.genre))}).take(24)
            val fromLocal=local.map {song->OdifyRecommendation(ExploreTrack(song.id,song.title,song.artist,"",listOf(song.genre),0),song)}
            val related=recommendations.filter {genreMatches(genre,it.track.genres)}
            val catalog=(discovery.filter {genreMatches(genre,it.genres)}+online.value)
                .map {OdifyRecommendation(it,index.resolve(it))}
            (related+catalog.take(18)+fromLocal+catalog.drop(18)).distinctBy {
                OdifyRecommendations.key(it.track.title,it.track.artist)
            }.take(60)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={onSelect("All")}){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"장르 둘러보기")}
            Text(genre.name,fontSize=23.sp,fontWeight=FontWeight.Bold,color=OdifyPalette.text)
        }
        if(results.isEmpty())Text(if(loading.value)"추천곡을 불러오는 중" else "이 장르의 곡을 찾지 못했어요.",modifier=Modifier.padding(12.dp),
            color=OdifyPalette.muted,fontSize=13.sp)
        val queue=remember(results){results.mapNotNull {it.owned}.distinctBy {it.id}}
        LazyColumn(contentPadding=PaddingValues(bottom=160.dp)) {
            items(results,key={OdifyRecommendations.key(it.track.title,it.track.artist)}) {row->
                HomeRecommendationTrack(row,{onOptions(row.owned ?: row.track.optionSong())}) {
                    val song=row.owned
                    if(song==null)onOnline(row.track) else onPlay(queue,queue.indexOfFirst {it.id==song.id})
                }
            }
        }
    }
}
