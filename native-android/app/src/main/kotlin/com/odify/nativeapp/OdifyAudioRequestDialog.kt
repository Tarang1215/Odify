package com.odify.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun OdifyAudioRequestDialog(song: OdifySong, onDismiss: () -> Unit,
    onSubmit: (String, String, String) -> Unit) {
    val context = LocalContext.current
    val repository = remember { OdifyAudioRequests(context.applicationContext) }
    var mode by remember(song.id) { mutableStateOf("") }
    var link by remember(song.id) { mutableStateOf("") }
    var reason by remember(song.id) { mutableStateOf("") }
    var status by remember(song.id) { mutableStateOf("") }
    val validLink = remember(link) { runCatching { OdifyAudioRequestPolicy.youtubeUrl(link) }.isSuccess }
    LaunchedEffect(song.id) {
        while (true) {
            val current = withContext(Dispatchers.IO) { repository.latest(song.id) }
            status = when (current?.optString("status")) {
                "LOCAL_PENDING" -> "휴대폰에 저장됨 · Drive 전송 대기"
                "QUEUED" -> "PC 처리 대기"
                "REPORTED" -> "오류 신고됨 · 다음 수집 작업에서 재검토"
                "PROCESSING" -> "새 음원 검증 및 교체 중"
                "COMPLETED" -> "교체 완료 · Drive 동기화 후 새 음원 재생"
                "SOURCE_PAUSED" -> "음원 제공처의 인증·요청 제한으로 대기 중"
                "CONFLICT" -> "파일이 변경되어 확인 필요 · 기존 음원 보존"
                "FAILED" -> "처리 실패 · 기존 음원 보존"
                else -> ""
            }
            delay(3000)
        }
    }
    OdifyGlassAlertDialog(onDismissRequest = onDismiss,
        title = { Text("오디오 변경 요청") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium)
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            if (mode.isBlank()) {
                OutlinedButton(onClick = { mode = "report" }, modifier = Modifier.fillMaxWidth()) { Text("Report") }
                OutlinedButton(onClick = { mode = "paste_link" }, enabled = song.id.startsWith("gdrive:"),
                    modifier = Modifier.fillMaxWidth()) { Text("Paste link") }
                Text("Report는 오류만 기록합니다. Paste link는 PC가 연결되어 있을 때 해당 영상으로 Drive 음원을 교체합니다.", style = MaterialTheme.typography.bodySmall)
            } else {
                if (mode == "paste_link") OutlinedTextField(value = link, onValueChange = { link = it },
                    label = { Text("YouTube 영상 링크") }, singleLine = true,
                    isError = link.isNotBlank() && !validLink)
                OutlinedTextField(value = reason, onValueChange = { reason = it.take(500) },
                    label = { Text("사유 (선택) · 라이브, 다른 곡 등") }, maxLines = 3)
                Text(if (mode == "report") "현재 음원은 그대로 재생됩니다. 다음 수집 작업에서 교체 후보를 찾습니다."
                    else "기존 음원을 백업하고 같은 Drive 파일 ID로 교체합니다. 오프라인에 따로 저장한 파일은 유지됩니다.",
                    style = MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = { if (mode.isNotBlank()) TextButton(
            enabled = mode == "report" || validLink,
            onClick = { onSubmit(mode, link, reason); onDismiss() }) { Text(if (mode == "report") "신고 저장" else "교체 요청") } },
        dismissButton = { TextButton(onClick = { if (mode.isBlank()) onDismiss() else mode = "" }) { Text(if (mode.isBlank()) "닫기" else "뒤로") } })
}
