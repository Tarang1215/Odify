package com.odify.nativeapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

private val artistCountries=linkedMapOf("kr" to "한국","jp" to "일본","us" to "미국")

@Composable internal fun OdifyTrendingArtists(active:Boolean,onBack:()->Unit,onProfile:()->Unit,
    onArtist:(String)->Unit,portrait:suspend(String)->String?,recommend:suspend(String,Set<String>)->List<String>) {
    val context=LocalContext.current
    val repository=remember(context){OdifyPopularArtists(context)}
    val revision by OdifyLastFmKeyStore.revision.collectAsState()
    var country by rememberSaveable {mutableStateOf("")}
    val back={if(country.isBlank())onBack() else country=""}
    BackHandler(active,onBack=back)
    val ranking=remember(country,revision){mutableStateOf<List<String>>(emptyList())}
    val suggestions=remember(country,revision){mutableStateOf<List<String>>(emptyList())}
    val status=remember(country,revision){mutableStateOf("")}
    LaunchedEffect(country,revision,active) {
        if(country.isBlank() || !active)return@LaunchedEffect
        status.value="인기 아티스트를 불러오는 중"
        try {
            ranking.value=repository.load(country,false).artists.map {it.name}.distinctBy(OdifyMusicMatching::artistKey)
            status.value=""
        } catch(cancel:CancellationException){throw cancel}
        catch(_:Exception) {
            status.value=if(withContext(Dispatchers.IO){OdifyLastFmKeyStore(context).read()}==null)
                "Last.fm을 연결하면 인기 아티스트를 볼 수 있어요." else "인기 아티스트를 불러오지 못했어요."
        }
    }
    LaunchedEffect(country,ranking.value,active,recommend) {
        if(country.isNotBlank() && active) {
            suggestions.value=recommend(country,ranking.value.map(OdifyMusicMatching::artistKey).toSet())
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal=24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=back) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"뒤로")}
            Text(artistCountries[country] ?: "Trending Artists",fontSize=27.sp,fontWeight=FontWeight.Bold,
                color=OdifyPalette.text,modifier=Modifier.weight(1f))
        }
        if(country.isBlank()) {
            artistCountries.forEach { (code,label) ->
                OdifyBrowseRow(label,Icons.Outlined.Public) {country=code}
            }
        } else key(country) {
            LazyVerticalGrid(columns=GridCells.Fixed(3),contentPadding=PaddingValues(top=8.dp,bottom=160.dp),
                horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                fun LazyGridScope.artistSection(label:String,names:List<String>) {
                    item(span={GridItemSpan(maxLineSpan)}) {
                        Text(label,fontSize=19.sp,fontWeight=FontWeight.Bold,color=OdifyPalette.text)
                    }
                    items(names.take(12),key={label+OdifyMusicMatching.artistKey(it)}) {name ->
                        Column(Modifier.fillMaxWidth().clickable {onArtist(name)}.padding(vertical=4.dp),
                            horizontalAlignment=Alignment.CenterHorizontally) {
                            HomeArtistPortrait(name,portrait,Modifier.fillMaxWidth(.9f).aspectRatio(1f))
                            Text(name,fontSize=12.sp,fontWeight=FontWeight.Medium,maxLines=2,
                                overflow=TextOverflow.Ellipsis,color=OdifyPalette.text,
                                textAlign=TextAlign.Center,modifier=Modifier.padding(top=8.dp))
                        }
                    }
                }
                artistSection("인기 아티스트",ranking.value)
                if(status.value.isNotBlank())item(span={GridItemSpan(maxLineSpan)}) {
                    Column {
                        Text(status.value,fontSize=13.sp,color=OdifyPalette.muted)
                        if(status.value.contains("연결하면"))TextButton(onClick=onProfile){Text("Last.fm 연결")}
                    }
                }
                if(suggestions.value.isNotEmpty())artistSection("추천 아티스트",suggestions.value)
            }
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
        if(entered.isNotBlank() && !entered.matches(Regex("[a-fA-F0-9]{32}")))
            Text("API 키 32자리를 붙여넣어 주세요.",style=MaterialTheme.typography.bodySmall,color=OdifyPalette.muted)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            TextButton(enabled=!busy && entered.matches(Regex("[a-fA-F0-9]{32}")),onClick={scope.launch {
                busy=true;message="연결을 확인하고 있어요."
                try {
                    OdifyPopularArtists(context).validate(entered)
                    withContext(Dispatchers.IO){OdifyLastFmKeyStore(context).save(entered)}
                    entered="";linked=true;message="연결했어요. 탐색에서 나라별 아티스트를 확인해 보세요."
                } catch(cancel:CancellationException){throw cancel}
                catch(error:OdifyLastFmError){message=error.message.orEmpty()}
                catch(_:Exception){message="API 키를 확인하거나 기기에 저장하지 못했어요."}
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
