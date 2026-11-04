package io.github.unfo17.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ListeningHistory(onDismiss: () -> Unit, onControls: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ListeningStore(context) }
    val library = remember { Library.get(context) }
    val state by ListeningSession.state.collectAsState()
    var revision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(store) { val observer = store.observe { revision++ }; onDispose { observer.close() } }
    val cursors = remember(revision) { store.cursors() }
    val books = remember(revision) { library.records().associateBy { it.id } }
    val activeIds = remember(revision) { books.values.filterNot { it.removed }.map { it.id }.toSet() }
    val seriesDefinitions = remember(revision) { library.series.definitions().associateBy { it.id } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 680.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Row { Text("听书记录与续听", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); TextButton(onClick = onDismiss) { Text("关闭") } } }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (cursors.isEmpty()) item { Text("开始听书后会保存位置。停止播放或重启应用后，可从这里继续。") }
            items(cursors, key = { it.ownerId }) { cursor ->
                val book = books[cursor.bookId]
                val series = cursor.seriesId?.let { seriesDefinitions[it] }?.takeIf { !it.deleted && it.volumes.any { part -> part.bookId == cursor.bookId } }
                val needed = series?.volumes?.let { parts -> if (cursor.completed) parts else parts.dropWhile { it.bookId != cursor.bookId } }?.map { it.bookId } ?: listOf(cursor.bookId)
                val available = activeIds.containsAll(needed)
                val current = state.active && (state.seriesId ?: state.bookId) == cursor.ownerId
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(series?.title ?: book?.title ?: cursor.title, style = MaterialTheme.typography.titleMedium)
                    Text(if (cursor.completed) "已听完" else java.time.Instant.ofEpochMilli(cursor.updatedAt).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), style = MaterialTheme.typography.labelSmall)
                    val quote = runCatching { Locator.fromJSON(JSONObject(cursor.locator))?.text?.highlight }.getOrNull()
                    if (!quote.isNullOrBlank()) Text(quote, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (!available) Text("原书已移除，重新导入后可续听。")
                    TextButton(enabled = available, onClick = {
                        try {
                            if (current) onControls()
                            else {
                                if (cursor.completed) ListeningSession.start(context, series?.volumes?.firstOrNull()?.bookId ?: cursor.bookId, series?.id, null)
                                else ListeningSession.continueFromRecord(context, cursor.ownerId)
                                onDismiss()
                            }
                        } catch (e: Exception) { error = e.message ?: "无法继续听书" }
                    }) { Text(if (current) "打开听书控制" else if (cursor.completed) "重新朗读" else "继续听书") }
                } }
            }
        }
    }
}
