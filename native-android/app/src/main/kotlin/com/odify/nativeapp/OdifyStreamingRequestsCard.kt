package com.odify.nativeapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun OdifyStreamingRequestsCard() {
    val context=LocalContext.current
    val repo=remember {OdifyStreamingRequests(context.applicationContext)}
    val scope=rememberCoroutineScope()
    var message by remember {mutableStateOf("20초 이상 들은 YouTube 곡을 휴대폰에 자동 저장합니다. Drive 업로드는 보류 중입니다.")}
    var recent by remember {mutableStateOf<List<org.json.JSONObject>>(emptyList())}
    val status by produceState("",repo) {
        while(true) {
            val rows=withContext(Dispatchers.IO){repo.read()}
            recent=(0 until rows.length()).map {rows.getJSONObject(it)}.takeLast(4).reversed()
            val counts=(0 until rows.length()).map {rows.getJSONObject(it).optString("status")}.groupingBy {it}.eachCount()
            value="저장 ${counts["SAVED_PHONE"] ?: 0} · 대기 ${(counts["QUEUED_PHONE"] ?: 0)+(counts["DOWNLOADING_PHONE"] ?: 0)} · 확인 필요 ${(counts["HELD_PHONE"] ?: 0)+(counts["HELD_SOURCE"] ?: 0)}"
            kotlinx.coroutines.delay(2000)
        }
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri ->
        if(uri!=null) scope.launch {
            runCatching {withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use {it.write(repo.export().toByteArray(Charsets.UTF_8))}
                    ?: error("파일을 열지 못했습니다.")
            }}.onSuccess {message="보관 요청을 저장했습니다. PC 수집 작업에 불러올 수 있습니다."}
                .onFailure {message=it.message ?: "요청 저장 실패"}
        }
    }
    Card(modifier=Modifier.odifyNeutralPanel(),colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color.Transparent)) {
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("YouTube 보관 요청",style=MaterialTheme.typography.titleSmall,color=OdifyPalette.text)
            Text(message,style=MaterialTheme.typography.bodySmall,color=OdifyPalette.muted)
            Text(status,style=MaterialTheme.typography.labelMedium,color=OdifyPalette.text)
            recent.forEach {row ->
                val state=when(row.optString("status")) {"SAVED_PHONE"->"저장 완료";"DOWNLOADING_PHONE"->"저장 중 ${row.optInt("progress")}%";"QUEUED_PHONE"->"대기 중";"SKIPPED_AGE"->"연령 확인 · 건너뜀";else->"연결 확인 필요"}
                Text(row.optString("title")+" · "+state,maxLines=2,style=MaterialTheme.typography.labelSmall,color=OdifyPalette.muted)
                if(row.optString("status")=="DOWNLOADING_PHONE") LinearProgressIndicator(progress={row.optInt("progress")/100f},modifier=Modifier.fillMaxWidth())
                if(row.optString("error").isNotBlank()) Text(row.optString("error").takeLast(180),maxLines=3,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
            }
            TextButton(onClick={OdifyPhoneDownloads.schedule(context,update=true);message="엔진을 업데이트한 뒤 보류된 곡을 다시 시도합니다."}) {Text("엔진 업데이트 및 실패 곡 재시도")}
            TextButton(onClick={export.launch("Odify-YouTube-collection.json")}) {Text("보관 기록 내보내기")}
        }
    }
}
