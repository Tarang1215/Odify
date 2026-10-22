package com.odify.nativeapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Browse Drive folders once; select the folder to index recursively forever. */
@Composable
fun OdifyDriveFolderDialog(
    onDismiss: () -> Unit,
    onSelect: (String, String) -> Unit
) {
    val context = LocalContext.current
    var stack by remember { mutableStateOf(listOf("root" to "My Drive")) }
    val folder = stack.last()
    var children by remember { mutableStateOf<List<OdifyDriveFile>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(folder.first) {
        busy = true
        error = ""
        children = emptyList()
        runCatching {
            withContext(Dispatchers.IO) {
                OdifyDriveApi.list(context, folder.first).filter { it.folder }
            }
        }.onSuccess { children = it }
         .onFailure { error = it.message ?: "Unable to list folders" }
        busy = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("음악 폴더 선택") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(stack.joinToString(" › ") { it.second },
                    style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(12.dp))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (children.isEmpty() && !busy && error.isEmpty()) {
                    Text("하위 폴더가 없습니다. 이 폴더를 선택할 수 있습니다.")
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(children, key = { it.id }) { child ->
                        Row(Modifier.fillMaxWidth().clickable {
                            stack = stack + (child.id to child.name)
                        }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null)
                            Spacer(Modifier.width(10.dp))
                            Text(child.name, Modifier.weight(1f), maxLines = 2)
                            Icon(Icons.Default.ChevronRight, null)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(folder.first, folder.second) },
                enabled = !busy && error.isBlank()) {
                Text("이 폴더를 음악 라이브러리로 지정")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (stack.size > 1) stack = stack.dropLast(1)
                else onDismiss()
            }) { Text(if (stack.size > 1) "상위 폴더" else "취소") }
        }
    )
}