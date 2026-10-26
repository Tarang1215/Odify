package com.odify.nativeapp

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.CancellationException

private val homeInk = OdifyPalette.text
private val homeGrey = OdifyPalette.muted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OdifyPersonalHome(
    recent: List<OdifySong>, hasHistory: Boolean, mixes: List<OdifyMix>,
    playlists: List<OdifyPlaylist>, onProfile: () -> Unit, onExplore: () -> Unit,
    onSearch: () -> Unit, onLibrary: () -> Unit, onPlay: (List<OdifySong>, Int) -> Unit,
    onSaveMix: (OdifyMix) -> Unit, onPlaylistPlay: (OdifyPlaylist) -> Unit,
    catalogSongs:List<OdifySong> = recent+mixes.flatMap {it.songs},
    discoveryTracks:List<ExploreTrack> = emptyList(),
    loadArtistTracks:suspend (String)->List<ExploreTrack> = {emptyList()},
    onPlayOnline:(ExploreTrack)->Unit = {},onArtistSearch:(String)->Unit = {},
    loadMoodTracks:suspend (String)->List<ExploreTrack> = {emptyList()}
) {
    var selectedMix by remember { mutableStateOf<OdifyMix?>(null) }
    var artistTracks by remember {mutableStateOf<List<ExploreTrack>>(emptyList())}
    var artistLoading by remember {mutableStateOf(false)}
    var artistError by remember {mutableStateOf(false)}
    var artistRetry by remember {mutableIntStateOf(0)}
    val artistName=selectedMix?.key?.takeIf {it.startsWith("artist:")}?.removePrefix("artist:")
    val latestArtistLoader by rememberUpdatedState(loadArtistTracks)
    val latestMoodLoader by rememberUpdatedState(loadMoodTracks)
    val moodKey=selectedMix?.key?.takeIf {it.startsWith("mood:")}?.removePrefix("mood:")
    LaunchedEffect(artistName,moodKey,artistRetry) {
        // The retained sheet still draws its last rows during closing.
        if(artistName==null && moodKey==null) {artistLoading=false;return@LaunchedEffect}
        artistTracks=emptyList();artistError=false;artistLoading=false
        artistLoading=true
        try {artistTracks=if(artistName!=null)latestArtistLoader(artistName) else latestMoodLoader(moodKey!!)}
        catch(cancel:CancellationException) {throw cancel}
        catch(_:Exception) {artistError=true}
        finally {artistLoading=false}
    }
    val newTracks=remember(discoveryTracks,catalogSongs) {OdifyDiscovery.newTracks(discoveryTracks,catalogSongs)}
    val moodMixes=remember(catalogSongs) {OdifyDiscovery.moods.associate {mood ->mood.key to
        OdifyMix("mood:${mood.key}",mood.name,mood.description,
            OdifySongPresentation.visible(catalogSongs).filter {OdifyDiscovery.matches(mood,listOf(it.genre))}.take(40))}}
    val moodOnline=remember(newTracks) {OdifyDiscovery.moods.associate {mood ->mood.key to
        newTracks.filter {OdifyDiscovery.matches(mood,it.genres)}.take(30)}}
    var category by remember {mutableStateOf("daily")}
    val hero = mixes.firstOrNull {it.key==category} ?: mixes.firstOrNull()
    val artists=remember(recent,catalogSongs) {(recent+catalogSongs).distinctBy {it.id}.filter {it.artist.isNotBlank()}
        .groupBy {it.artist}.entries.take(8)}
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp, 14.dp, 22.dp, 160.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick=onSearch,modifier=Modifier.size(48.dp)) {Icon(Icons.Outlined.Search,"검색",tint=homeInk,modifier=Modifier.size(22.dp))}
                Text("Odify", fontSize = 23.sp, fontWeight = FontWeight.Bold,
                    color = homeInk, letterSpacing = (-.9).sp,modifier=Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = onProfile, modifier = Modifier.size(48.dp)) {
                    Box(Modifier.size(36.dp).background(OdifyPalette.surface, RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PersonOutline, "프로필 및 설정", tint = homeInk, modifier = Modifier.size(21.dp))
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                listOf("For you" to "daily","On repeat" to "repeat","Discover" to "unheard","Offline" to "offline")
                    .filter {it.second=="daily" || mixes.any {mix -> mix.key==it.second}}.forEach {(label,key) ->
                    OdifyPill(label,category==key) {category=key}
                }
            }
            Spacer(Modifier.height(12.dp))
            if (hero != null) {
                Box(Modifier.fillMaxWidth().height(164.dp).clip(RoundedCornerShape(25.dp))
                    .clickable { selectedMix = hero }) {
                    AlbumArt(hero.coverSong(), Modifier.fillMaxSize(), radius = RoundedCornerShape(25.dp))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                        Color.Black.copy(alpha=.08f), Color.Black.copy(alpha=.78f)))))
                    Column(Modifier.align(Alignment.BottomStart).padding(21.dp).fillMaxWidth(.8f)) {
                        Text(when(java.time.LocalTime.now().hour) {in 5..11 -> "GOOD MORNING";in 12..17 -> "GOOD AFTERNOON";else -> "GOOD EVENING"}, fontSize=9.sp, letterSpacing=1.7.sp, color=Color.White.copy(alpha=.8f))
                        Spacer(Modifier.height(7.dp))
                        Text(hero.name, fontSize=27.sp, fontWeight=FontWeight.Bold, color=Color.White)
                        Spacer(Modifier.height(5.dp))
                        Text(hero.reason, fontSize=11.sp, color=Color.White.copy(alpha=.8f), maxLines=2)
                    }
                    FilledIconButton(onClick={onPlay(hero.songs,0)}, Modifier.align(Alignment.BottomEnd).padding(16.dp).size(46.dp),
                        colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color.White,contentColor=OdifyPalette.background)) {
                        Icon(Icons.Outlined.PlayArrow,"${hero.name} 재생")
                    }
                }
            } else {
                Text("음악을 연결하고, 나만의 리듬을 찾아요.",color=homeGrey,fontSize=14.sp)
                TextButton(onClick=onProfile) { Text("음악 소스 연결",color=homeInk) }
            }
            Spacer(Modifier.height(27.dp))
        }
        if (recent.isNotEmpty()) item {
            Text(if(hasHistory) "Your recent moments" else "Ready when you are",fontSize=20.sp,fontWeight=FontWeight.Bold,color=homeInk)
            Text(if(hasHistory) "최근 함께한 음악" else "내 음악에서 골라 들어요",fontSize=11.sp,color=homeGrey,
                modifier=Modifier.padding(top=4.dp,bottom=14.dp))
            LazyRow(horizontalArrangement=Arrangement.spacedBy(13.dp)) {
                items(recent.take(10),key={it.id}) {song ->
                    Column(Modifier.width(106.dp).clickable {onPlay(recent,recent.indexOf(song))}) {
                        AlbumArt(song,Modifier.size(106.dp),radius=RoundedCornerShape(15.dp))
                        Text(song.title,fontSize=12.sp,color=homeInk,maxLines=1,overflow=TextOverflow.Ellipsis,
                            modifier=Modifier.padding(top=8.dp),fontWeight=FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
        if(artists.isNotEmpty()) item {
            Text("More from your artists",fontSize=20.sp,fontWeight=FontWeight.Bold,color=homeInk,modifier=Modifier.padding(top=24.dp,bottom=13.dp))
            LazyRow(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                items(artists,key={it.key}) {entry ->
                    Column(Modifier.width(90.dp).clickable {selectedMix=OdifyMix("artist:${entry.key}",entry.key,"내 음악과 이 아티스트의 더 많은 곡 · 저장하지 않은 곡은 YouTube로",entry.value.distinctBy {it.id})},
                        horizontalAlignment=Alignment.CenterHorizontally) {
                        AlbumArt(entry.value.first(),Modifier.size(82.dp),radius=androidx.compose.foundation.shape.CircleShape)
                        Text(entry.key,fontSize=11.sp,color=homeInk,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=8.dp))
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
        if (mixes.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                Text("Made for you",fontSize=20.sp,fontWeight=FontWeight.Bold,color=homeInk,modifier=Modifier.weight(1f))
                Icon(Icons.Outlined.AutoAwesome,null,tint=homeGrey,modifier=Modifier.size(18.dp))
            }
            Text("들을수록 가까워지는 취향",fontSize=11.sp,color=homeGrey,modifier=Modifier.padding(top=4.dp,bottom=14.dp))
            LazyRow(horizontalArrangement=Arrangement.spacedBy(13.dp)) {
                items(mixes,key={it.key}) { mix ->
                    Column(Modifier.width(132.dp).clickable {selectedMix=mix}) {
                        Box(Modifier.size(132.dp).clip(RoundedCornerShape(19.dp))) {
                            AlbumArt(mix.coverSong(),Modifier.fillMaxSize(),radius=RoundedCornerShape(19.dp))
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.6f)))))
                            Text(mix.name,modifier=Modifier.align(Alignment.BottomStart).padding(13.dp),fontSize=18.sp,
                                fontWeight=FontWeight.SemiBold,color=Color.White,maxLines=2)
                        }
                        Text("${mix.songs.size}곡 · 나만의 믹스",fontSize=11.sp,color=homeGrey,modifier=Modifier.padding(top=7.dp))
                    }
                }
            }
            Spacer(Modifier.height(29.dp))
        }
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("Find your mood",fontSize=20.sp,color=homeInk,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
            }
            Text("장르로 고르는 지금의 기분 · 내 음악과 새로운 곡",fontSize=11.sp,color=homeGrey,modifier=Modifier.padding(top=5.dp,bottom=14.dp))
            LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                items(OdifyDiscovery.moods,key={it.key}) {mood ->
                    val owned=moodMixes.getValue(mood.key)
                    val count=owned.songs.size+moodOnline.getValue(mood.key).size
                    Column(Modifier.width(190.dp).height(116.dp).odifyNeutralPanel(22)
                        .clickable {selectedMix=owned}.padding(17.dp),verticalArrangement=Arrangement.SpaceBetween) {
                        Icon(when(mood.key) {"focus"->Icons.Outlined.Headphones;"energy"->Icons.Outlined.Bolt;"night"->Icons.Outlined.NightsStay;else->Icons.Outlined.Spa},null,tint=homeGrey,modifier=Modifier.size(23.dp))
                        Text(mood.name,fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=homeInk,maxLines=1)
                        Text(if(count>0)"${count}곡 · 장르 기반" else "새로운 음악 찾아보기",fontSize=10.sp,color=homeGrey,maxLines=1)
                    }
                }
            }
        }
        if(newTracks.isNotEmpty()) item {
            Spacer(Modifier.height(28.dp))
            Text("Beyond your collection",fontSize=20.sp,color=homeInk,fontWeight=FontWeight.Bold)
            Text("한국 · 일본 · 미국 차트에서 찾은, 아직 없는 음악",fontSize=11.sp,color=homeGrey,modifier=Modifier.padding(top=5.dp,bottom=14.dp))
            newTracks.take(5).forEach {track -> HomeOnlineTrack(track) {onPlayOnline(track)}}
        }
        item {
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth().background(OdifyPalette.surface,RoundedCornerShape(20.dp)).clickable(onClick=onExplore).padding(19.dp),
                verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Outlined.Explore,null,tint=homeInk)
                Column(Modifier.weight(1f).padding(start=13.dp)) {
                    Text("A world beyond your mix",fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=homeInk)
                    Text("한국 · 일본 · 미국, 지금의 음악",fontSize=11.sp,color=homeGrey)
                }
                Icon(Icons.Outlined.ChevronRight,null,tint=homeGrey)
            }
            TextButton(onClick=onSearch,modifier=Modifier.padding(top=8.dp)) {Text("떠오르는 노래나 가사로 검색",color=homeGrey,fontSize=12.sp)}
        }
    }
    OdifyRetainedGlassSheet(selectedMix,onDismiss={selectedMix=null},maxHeightFraction=.84f,transparency=.28f) {mix ->
        val online=when {
            mix.key.startsWith("artist:") -> OdifyDiscovery.newTracks(artistTracks,catalogSongs).filterNot {track ->
                mix.songs.any {OdifyYouTubeMatcher.norm(it.title)==OdifyYouTubeMatcher.norm(track.title)}
            }
            mix.key.startsWith("mood:") -> OdifyDiscovery.newTracks(artistTracks+moodOnline[mix.key.removePrefix("mood:")].orEmpty(),catalogSongs)
            else -> emptyList()
        }
        Row(Modifier.fillMaxWidth().padding(bottom=14.dp),verticalAlignment=Alignment.CenterVertically) {
            AlbumArt(mix.coverSong(),Modifier.size(62.dp),if(mix.key.startsWith("artist:")) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(16.dp))
            Column(Modifier.weight(1f).padding(start=13.dp,end=4.dp)) {
                Text(mix.name,fontSize=19.sp,lineHeight=24.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text("내 음악 ${mix.songs.size}곡"+(if(online.isNotEmpty())" · 더 많은 곡 ${online.size}" else ""),fontSize=11.sp,color=homeGrey,modifier=Modifier.padding(top=5.dp))
            }
            IconButton(onClick={selectedMix=null},modifier=Modifier.size(42.dp)) {Icon(Icons.Outlined.Close,"컬렉션 닫기",Modifier.size(21.dp))}
        }
        Text(mix.reason,fontSize=12.sp,lineHeight=18.sp,color=homeGrey,maxLines=2,overflow=TextOverflow.Ellipsis,
            modifier=Modifier.fillMaxWidth().padding(start=4.dp,bottom=16.dp))
        Row(Modifier.fillMaxWidth().padding(bottom=16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Button(onClick={if(mix.songs.isNotEmpty())onPlay(mix.songs,0) else online.firstOrNull()?.let(onPlayOnline);selectedMix=null},
                enabled=mix.songs.isNotEmpty() || online.isNotEmpty(),modifier=Modifier.weight(1f).height(44.dp),
                contentPadding=PaddingValues(horizontal=10.dp),colors=ButtonDefaults.buttonColors(containerColor=Color.White,contentColor=Color.Black)) {
                Icon(Icons.Outlined.PlayArrow,null,Modifier.size(19.dp));Spacer(Modifier.width(5.dp));Text(if(mix.songs.isEmpty())"YouTube로 듣기" else "내 음악 재생",fontSize=12.sp,maxLines=1)
            }
            OutlinedButton(onClick={onSaveMix(mix)},enabled=mix.songs.isNotEmpty(),modifier=Modifier.weight(1f).height(44.dp),contentPadding=PaddingValues(horizontal=10.dp)) {
                Icon(Icons.Outlined.PlaylistAdd,null,Modifier.size(19.dp));Spacer(Modifier.width(5.dp));Text("내 음악 저장",fontSize=12.sp,maxLines=1)
            }
        }
        // Reserve the artist list's space while fetching so results never shove
        // an already-open sheet abruptly up the screen.
        val bodySize=if((mix.key.startsWith("artist:") || mix.key.startsWith("mood:")) && !LocalOdifySheetExpanded.current)Modifier.height(380.dp)
            else Modifier.heightIn(max=if(LocalOdifySheetExpanded.current) androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp else 380.dp)
        LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=LocalOdifySheetExpanded.current)
            .then(bodySize),contentPadding=PaddingValues(bottom=10.dp)) {
            if(artistLoading) item {Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(17.dp),strokeWidth=2.dp,color=homeGrey);Spacer(Modifier.width(10.dp));Text(if(mix.key.startsWith("artist:"))"아티스트의 곡을 찾고 있어요" else "이 기분에 어울리는 장르를 찾고 있어요",fontSize=12.sp,color=homeGrey)
            }}
            if(mix.key.startsWith("artist:") && !artistLoading && (artistError || online.isEmpty())) item {
                Text(if(artistError)"곡 목록에 연결하지 못했어요. 내 음악은 계속 볼 수 있어요." else "추가 곡을 찾지 못했어요. YouTube에서 더 찾아볼 수 있어요.",
                    fontSize=12.sp,color=homeGrey,modifier=Modifier.padding(8.dp))
                Row {
                    if(artistError) TextButton(onClick={artistRetry++}) {Text("다시 시도")}
                    TextButton(onClick={onArtistSearch(mix.name);selectedMix=null}) {Text("YouTube에서 더 찾기")}
                }
            }
            if(mix.key.startsWith("mood:") && artistError && !artistLoading) item {
                Text("새로운 곡 목록에 연결하지 못했어요. 준비된 음악은 계속 볼 수 있어요.",fontSize=12.sp,color=homeGrey,modifier=Modifier.padding(8.dp))
                TextButton(onClick={artistRetry++}) {Text("다시 시도")}
            }
            itemsIndexed(mix.songs,key={_,song->song.id}) {index,song ->
                Row(Modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(13.dp))
                    .clickable {onPlay(mix.songs,index);selectedMix=null}.padding(horizontal=5.dp),verticalAlignment=Alignment.CenterVertically) {
                    AlbumArt(song,Modifier.size(44.dp),RoundedCornerShape(10.dp))
                    Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
                        Text(song.title,fontSize=13.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(song.artist,fontSize=11.sp,color=homeGrey,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
                    }
                    if(song.durationMs>0) Text("${song.durationMs/60000}:${(song.durationMs/1000%60).toString().padStart(2,'0')}",fontSize=10.sp,color=homeGrey)
                }
            }
            if(online.isNotEmpty()) item {Text("더 많은 곡 · YouTube로 듣기",fontSize=12.sp,color=homeGrey,modifier=Modifier.padding(top=18.dp,bottom=8.dp))}
            items(online,key={"online:${it.id}"}) {track ->HomeOnlineTrack(track) {onPlayOnline(track);selectedMix=null}}
            if(mix.songs.isEmpty() && online.isEmpty() && !artistLoading && !artistError && !mix.key.startsWith("artist:")) item {
                Text("아직 이 장르의 곡이 없어요. 검색에서 취향을 더해 보세요.",fontSize=13.sp,color=homeGrey,modifier=Modifier.padding(12.dp))
                TextButton(onClick={onSearch();selectedMix=null}) {Text("음악 찾아보기")}
            }
        }
    }
}

@Composable private fun HomeOnlineTrack(track:ExploreTrack,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(13.dp)).clickable(onClick=onClick).padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(Color.White.copy(alpha=.055f),RoundedCornerShape(11.dp)),contentAlignment=Alignment.Center) {
            Icon(Icons.Outlined.MusicNote,null,tint=homeGrey,modifier=Modifier.size(21.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
            Text(track.title,fontSize=13.sp,fontWeight=FontWeight.Medium,color=homeInk,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(track.artist,fontSize=11.sp,color=homeGrey,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
        }
        Text("YouTube",fontSize=9.sp,color=homeGrey)
    }
}

/** Keep a missing first-track image from hiding artwork available within the mix. */
private fun OdifyMix.coverSong(): OdifySong? =
    songs.firstOrNull { !it.artworkPath.isNullOrBlank() } ?: songs.firstOrNull()
