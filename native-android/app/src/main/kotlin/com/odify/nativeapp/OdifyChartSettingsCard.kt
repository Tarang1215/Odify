package com.odify.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable internal fun OdifyChartSettingsCard() {
    val context=LocalContext.current
    val prefs=remember {context.getSharedPreferences("odify_chart_schedule",0)}
    var weekly by remember {mutableStateOf(prefs.getBoolean("weekly",false))}
    var message by remember {mutableStateOf("바뀐 곡을 수집 목록에 기록하고, 없는 음원은 순서대로 저장해요.")}
    val status by produceState("",Unit) {
        while(true) {
            value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val rows=OdifyChartRequests(context).read()
                val pending=(0 until rows.length()).count {rows.getJSONObject(it).optString("status")=="AWAITING_LINK"}
                val last=prefs.getLong("last_refresh",0)
                (if(last>0) "최근 확인 · "+java.text.SimpleDateFormat("MM.dd HH:mm",java.util.Locale.KOREA).format(java.util.Date(last)) else "아직 자동 갱신 전이에요")+" · 확인 대기 ${pending}곡"
            }
            kotlinx.coroutines.delay(3000)
        }
    }
    Column(Modifier.fillMaxWidth().odifyNeutralPanel().padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
        Text("차트 자동 업데이트",style=MaterialTheme.typography.titleMedium)
        Text(message,style=MaterialTheme.typography.bodySmall,color=OdifyPalette.muted)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OdifyPill("매일",!weekly) {weekly=false;prefs.edit().putBoolean("weekly",false).apply();OdifyChartAutomation.schedule(context)}
            OdifyPill("매주",weekly) {weekly=true;prefs.edit().putBoolean("weekly",true).apply();OdifyChartAutomation.schedule(context)}
        }
        Text(status,style=MaterialTheme.typography.labelSmall,color=OdifyPalette.muted)
        TextButton(onClick={OdifyChartAutomation.now(context);message="차트 갱신과 새 곡 확인을 예약했어요."}) {Text("지금 업데이트")}
    }
}
