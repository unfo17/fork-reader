package io.github.unfo17.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FontManager(onDismiss: () -> Unit, selected: String, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { FontStore(context) }
    val scope = rememberCoroutineScope()
    var fonts by remember { mutableStateOf(store.fonts()) }
    val catalogue = remember { store.catalogue() }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var licenses by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var deleting by remember { mutableStateOf<ReaderFont?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) operation = scope.launch {
        busy = true; error = null
        try { store.import(uri); fonts = store.fonts() }
        catch (_: kotlinx.coroutines.CancellationException) { error = "已取消" }
        catch (e: Exception) { error = e.message ?: "字体导入失败" }
        finally { busy = false }
    } }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 650.dp), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("阅读字体", style = MaterialTheme.typography.titleLarge) }
            item { Text("导入 TTF / OTF，单款 32 MiB 以内，最多保留 20 款。下载与导入后可离线使用。") }
            item { OutlinedButton(enabled = !busy, onClick = { importer.launch(arrayOf("*/*")) }) { Text("导入字体") } }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(progress.ifBlank { "正在验证字体…" }); TextButton(onClick = { operation?.cancel() }) { Text("取消") } }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            item { TextButton(enabled = !busy, onClick = { onSelect("") }) { Text(if (selected.isEmpty()) "✓ 系统字体" else "系统字体") } }
            items(fonts, key = { it.id }) { font ->
                Row {
                    TextButton(enabled = !busy, onClick = { onSelect(font.id) }, modifier = Modifier.weight(1f)) { Text((if (selected == font.id) "✓ " else "") + font.name) }
                    TextButton(enabled = !busy, onClick = { deleting = font }) { Text("移除") }
                }
            }
            item { Text("可下载字体", style = MaterialTheme.typography.titleMedium) }
            items(catalogue.filter { entry -> fonts.none { it.id == entry.id } }, key = { it.id }) { entry ->
                ListItem(headlineContent = { Text(entry.name) }, supportingContent = { Text(entry.description + " · %.1f MiB".format(entry.transferBytes / 1048576.0)) }, trailingContent = {
                    TextButton(enabled = !busy, onClick = { operation = scope.launch {
                        busy = true; error = null; progress = "正在下载 ${entry.name}"
                        try {
                            store.download(entry) { received, total -> scope.launch { progress = "${entry.name} ${(received * 100 / total).coerceIn(0, 100)}%" } }
                            fonts = store.fonts()
                        } catch (_: kotlinx.coroutines.CancellationException) { error = "已取消" }
                        catch (e: Exception) { error = e.message ?: "字体下载失败" }
                        finally { busy = false; progress = "" }
                    } }) { Text("下载") }
                })
            }
            item { TextButton(onClick = { licenses = true }) { Text("字体版权与开源许可") } }
        }
    }
    if (licenses) Licenses { licenses = false }
    deleting?.let { font -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("移除 ${font.name}？") }, text = { Text("使用该字体的书籍将恢复系统字体，书籍与阅读记录保留。") }, confirmButton = { TextButton(onClick = { scope.launch {
        busy = true
        try { store.remove(font); fonts = store.fonts(); if (selected == font.id) onSelect("") }
        catch (e: Exception) { error = e.message ?: "移除失败" }
        finally { busy = false; deleting = null }
    } }) { Text("移除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}
