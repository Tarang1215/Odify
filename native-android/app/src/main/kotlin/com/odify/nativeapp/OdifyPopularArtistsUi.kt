package com.odify.nativeapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

@Composable internal fun OdifyPopularArtistsRow(country:String,refresh:Int,onProfile:()->Unit,
    onArtist:(String)->Unit,portrait:suspend(String)->String?) {
    val context=LocalContext.current
    val repository=remember(context){OdifyPopularArtists(context)}
    val revision by OdifyLastFmKeyStore.revision.collectAsState()
    var ranking by remember(country) {mutableStateOf<OdifyArtistRanking?>(null)}
    var message by remember(country) {mutableStateOf("국가별 순위를 확인하고 있어요.")}
    LaunchedEffect(country,refresh,revision) {
        ranking=null
        try {ranking=repository.load(country,refresh>0);message=""}
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){message=if(withContext(Dispatchers.IO){OdifyLastFmKeyStore(context).read()}==null)
            "Last.fm을 연결하면 이 나라에서 인기 있는 아티스트를 볼 수 있어요."
            else "순위를 불러오지 못했어요. 연결을 확인하고 새로고침해 주세요."}
    }
    Column(Modifier.fillMaxWidth().padding(top=6.dp,bottom=20.dp)) {
        Text("인기 아티스트",fontSize=19.sp,fontWeight=FontWeight.SemiBold,color=OdifyPalette.text)
        Text("Last.fm · 선택한 나라의 청취 인기",fontSize=10.sp,color=OdifyPalette.muted,modifier=Modifier.padding(top=4.dp,bottom=12.dp))
        val data=ranking
        if(data==null) {
            Text(message,fontSize=12.sp,color=OdifyPalette.muted)
            if(message.contains("연결하면"))TextButton(onClick=onProfile){Text("Last.fm 연결")}
        } else {
            LazyRow(horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                items(data.artists,key={it.name}) {artist ->
                    Column(Modifier.width(92.dp).clickable {onArtist(artist.name)},horizontalAlignment=Alignment.CenterHorizontally) {
                        HomeArtistPortrait(artist.name,portrait,Modifier.size(78.dp))
                        Text("${artist.rank}. ${artist.name}",fontSize=11.sp,fontWeight=FontWeight.Medium,maxLines=2,
                            overflow=TextOverflow.Ellipsis,color=OdifyPalette.text,modifier=Modifier.padding(top=9.dp))
                    }
                }
            }
            val date=java.text.SimpleDateFormat("MM.dd HH:mm",java.util.Locale.KOREA).format(java.util.Date(data.fetchedAt))
            Text("$date 조회"+(if(data.cached)" · 저장된 순위" else ""),fontSize=10.sp,color=OdifyPalette.muted,modifier=Modifier.padding(top=10.dp))
        }
    }
}

@Composable internal fun OdifyLastFmSettingsCard() {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val uri=LocalUriHandler.current
    var entered by remember {mutableStateOf("")};var busy by remember {mutableStateOf(false)}
    var linked by remember {mutableStateOf(false)};var message by remember {mutableStateOf("")}
    LaunchedEffect(Unit){linked=withContext(Dispatchers.IO){OdifyLastFmKeyStore(context).read()!=null}}
    Column(Modifier.fillMaxWidth().odifyNeutralPanel().padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
        Text("국가별 인기 아티스트",style=MaterialTheme.typography.titleMedium)
        Text("Last.fm 청취 기준으로 한국·일본·미국의 인기 아티스트를 탐색에 표시해요. 아티스트 국적이나 실시간 급상승 순위는 아니에요.",
            style=MaterialTheme.typography.bodySmall,color=OdifyPalette.muted)
        Text(if(linked)"Last.fm 연결됨" else "Last.fm API 키가 필요해요",style=MaterialTheme.typography.labelMedium)
        OutlinedTextField(value=entered,onValueChange={entered=it.trim();message=""},label={Text("Last.fm API 키")},
            singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!busy,modifier=Modifier.fillMaxWidth())
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            TextButton(enabled=!busy && entered.matches(Regex("[a-fA-F0-9]{32}")),onClick={scope.launch {
                busy=true;message="연결을 확인하고 있어요."
                try {
                    OdifyPopularArtists(context).validate(entered)
                    withContext(Dispatchers.IO){OdifyLastFmKeyStore(context).save(entered)}
                    entered="";linked=true;message="연결했어요. 탐색에서 나라별 아티스트를 확인해 보세요."
                } catch(cancel:CancellationException){throw cancel}
                catch(_:Exception){message="키가 유효한지와 네트워크 연결을 확인해 주세요."}
                finally {busy=false}
            }}){Text(if(busy)"확인 중…" else "연결 확인 및 저장")}
            if(linked)TextButton(enabled=!busy,onClick={scope.launch {
                withContext(Dispatchers.IO){OdifyLastFmKeyStore(context).clear();listOf("kr","jp","us").forEach {java.io.File(context.cacheDir,"lastfm_artists_$it.json").delete()}}
                linked=false;message="연결을 해제했어요."
            }}){Text("연결 해제")}
        }
        if(message.isNotBlank())Text(message,style=MaterialTheme.typography.bodySmall,color=OdifyPalette.muted)
        TextButton(onClick={uri.openUri("https://www.last.fm/api/account/create")}){Text("API 키 발급 안내")}
    }
}
