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
    var message by remember {mutableStateOf("20초 이상 들은 YouTube 곡을 PC 보관 요청에 추가합니다. Drive 업로드는 보류 중입니다.")}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri ->
        if(uri!=null) scope.launch {
            runCatching {withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use {it.write(repo.export().toByteArray(Charsets.UTF_8))}
                    ?: error("파일을 열지 못했습니다.")
            }}.onSuccess {message="보관 요청을 저장했습니다. PC 수집 작업에 불러올 수 있습니다."}
                .onFailure {message=it.message ?: "요청 저장 실패"}
        }
    }
    Card(colors=CardDefaults.cardColors(containerColor=OdifyPalette.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("YouTube 보관 요청",style=MaterialTheme.typography.titleSmall,color=OdifyPalette.text)
            Text(message,style=MaterialTheme.typography.bodySmall,color=OdifyPalette.muted)
            TextButton(onClick={export.launch("Odify-YouTube-collection.json")}) {Text("PC 보관 요청 내보내기")}
        }
    }
}
