package io.github.unfo17.reader

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable fun ListeningMiniPlayer(onOpen: () -> Unit) {
    val state by ListeningSession.state.collectAsState()
    val context = LocalContext.current
    if (state.active || state.phase == ListeningPhase.FAILED) Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.title.ifBlank { if (state.phase == ListeningPhase.FAILED) "听书失败" else "正在准备听书" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (state.phase == ListeningPhase.FAILED) state.error ?: "听书失败" else if (state.phase == ListeningPhase.PAUSED) "听书已暂停" else "正在听书", style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (state.phase == ListeningPhase.FAILED) IconButton(onClick = onOpen) { Icon(Icons.Outlined.Info, "查看听书错误") }
            else IconButton(onClick = { ListeningSession.command(context, if (state.phase == ListeningPhase.PAUSED) ListeningSession.RESUME else ListeningSession.PAUSE) }) {
                Icon(if (state.phase == ListeningPhase.PAUSED) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, if (state.phase == ListeningPhase.PAUSED) "继续听书" else "暂停听书")
            }
            IconButton(onClick = { if (state.phase == ListeningPhase.FAILED) ListeningSession.dismissError() else ListeningSession.command(context, ListeningSession.STOP) }) { Icon(if (state.phase == ListeningPhase.FAILED) Icons.Outlined.Close else Icons.Outlined.Stop, if (state.phase == ListeningPhase.FAILED) "关闭听书提示" else "停止听书") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun ListeningControls(onDismiss: () -> Unit, onVoiceSettings: () -> Unit) {
    val state by ListeningSession.state.collectAsState()
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.title.ifBlank { "听书控制" }, style = MaterialTheme.typography.titleLarge)
            Text(when (state.phase) {
                ListeningPhase.PREPARING -> "正在准备语音"
                ListeningPhase.PLAYING -> "正在朗读"
                ListeningPhase.PAUSED -> "已暂停"
                ListeningPhase.COMPLETED -> "朗读完成"
                ListeningPhase.FAILED -> "朗读失败"
                ListeningPhase.STOPPED -> "已停止"
            })
            (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.text.isNotBlank()) Text(state.text)
            Row {
                Button(enabled = state.active, onClick = { ListeningSession.command(context, if (state.phase == ListeningPhase.PAUSED) ListeningSession.RESUME else ListeningSession.PAUSE) }) { Text(if (state.phase == ListeningPhase.PAUSED) "继续朗读" else "暂停朗读") }
                TextButton(enabled = state.active, onClick = { ListeningSession.command(context, ListeningSession.STOP) }) { Text("停止朗读") }
            }
            TextButton(onClick = onVoiceSettings) { Text("系统语音与音色") }
            Text("睡眠定时", style = MaterialTheme.typography.titleMedium)
            if (state.sleepAfterChapter) Text("本章结束后暂停朗读")
            else state.sleepUntil?.let { until -> Text("${java.time.Instant.ofEpochMilli(until).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))} 暂停朗读") }
                ?: Text("当前未设置定时")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 15, 30, 60).forEach { minutes -> TextButton(enabled = state.active, onClick = { ListeningSession.sleep(context, minutes) }) { Text("$minutes 分钟") } }
                TextButton(enabled = state.active, onClick = { ListeningSession.sleepAfterChapter(context, true) }) { Text("本章结束") }
                TextButton(enabled = state.active && (state.sleepUntil != null || state.sleepAfterChapter), onClick = { ListeningSession.sleep(context, 0) }) { Text("关闭定时") }
            }
            TextButton(enabled = state.bookId.isNotBlank(), onClick = {
                if (Library.get(context).books().none { it.id == state.bookId }) error = "原书已移除，请重新导入后阅读"
                else {
                    context.startActivity(Intent(context, ReaderActivity::class.java).putExtra("book", state.bookId).putExtra("series", state.seriesId).putExtra("jumpLocator", state.locator))
                    onDismiss()
                }
            }) { Text("阅读正在朗读的原文") }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}
