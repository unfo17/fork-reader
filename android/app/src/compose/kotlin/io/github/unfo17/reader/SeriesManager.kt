package io.github.unfo17.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable fun SeriesManager(library: Library, existing: BookSeries?, initialBooks: Set<String>, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val all = remember { library.books() }
    val elsewhere = remember { library.series.definitions().filter { !it.deleted && it.id != existing?.id }.flatMap { it.volumes.map { part -> part.bookId } }.toSet() }
    val hints = remember { all.filter { it.id in initialBooks }.associate { it.id to SeriesHint.infer(it.title) } }
    val suggested = remember { hints.isNotEmpty() && hints.values.all { it != null } && hints.values.map { it?.key }.distinct().size == 1 }
    var title by remember { mutableStateOf(existing?.title ?: if (suggested) hints.values.first()?.title.orEmpty() else "") }
    var volumes by remember { mutableStateOf(existing?.volumes?.map { it.bookId to it.title } ?: all.filter { it.id in initialBooks }.let { books -> if (suggested) books.sortedBy { hints[it.id]!!.number } else books }.map { it.id to (if (suggested) hints[it.id]!!.volumeTitle else it.title) }) }
    var author by remember { mutableStateOf(existing?.author ?: all.firstOrNull { it.id in initialBooks }?.author.orEmpty()) }
    var summary by remember { mutableStateOf(existing?.summary.orEmpty()) }
    var tags by remember { mutableStateOf(existing?.tags?.joinToString("，").orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var dissolve by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(if (existing == null) "合并为多卷书" else "管理分卷") }, text = {
        LazyColumn(Modifier.heightIn(max = 550.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (suggested && existing == null) item { Text("已按书名推测卷序，请核对后保存。") }
            item { Text("保留各卷原书、书签和阅读位置。调整顺序后章节标识保持不变。") }
            item { OutlinedTextField(title, { title = it }, label = { Text("合订本书名") }, enabled = !saving) }
            item { OutlinedTextField(author, { author = it }, label = { Text("作者") }, enabled = !saving) }
            item { OutlinedTextField(summary, { summary = it }, label = { Text("简介") }, enabled = !saving) }
            item { OutlinedTextField(tags, { tags = it }, label = { Text("标签（逗号分隔）") }, enabled = !saving) }
            itemsIndexed(volumes, key = { _, part -> part.first }) { index, (id, label) ->
                Column {
                    OutlinedTextField(label, { value -> volumes = volumes.map { if (it.first == id) id to value else it } }, label = { Text("第 ${index + 1} 卷名称") }, enabled = !saving)
                    Row {
                        IconButton(enabled = !saving && index > 0, onClick = { volumes = volumes.toMutableList().apply { add(index - 1, removeAt(index)) } }) { Icon(Icons.Outlined.ArrowUpward, "上移 $label") }
                        IconButton(enabled = !saving && index < volumes.lastIndex, onClick = { volumes = volumes.toMutableList().apply { add(index + 1, removeAt(index)) } }) { Icon(Icons.Outlined.ArrowDownward, "下移 $label") }
                        TextButton(enabled = !saving, onClick = { volumes = volumes.filterNot { it.first == id } }) { Text("移出合订本") }
                    }
                }
            }
            item { Text("添加已导入的原书", style = MaterialTheme.typography.titleSmall) }
            val available = all.filter { book -> book.id !in elsewhere && volumes.none { it.first == book.id } }
            itemsIndexed(available, key = { _, book -> "add-${book.id}" }) { _, book ->
                TextButton(enabled = !saving, onClick = { volumes = volumes + (book.id to book.title) }) { Text("＋ ${book.title}") }
            }
            if (existing != null) item { TextButton(enabled = !saving, onClick = { dissolve = true }) { Text("拆分为独立书籍") }; TextButton(enabled = !saving, onClick = { delete = true }) { Text("删除整部书的本机文件", color = MaterialTheme.colorScheme.error) } }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
    }, confirmButton = { TextButton(enabled = !saving && volumes.size >= 2 && title.isNotBlank(), onClick = {
        saving = true
        scope.launch {
            try { library.saveSeries(existing?.id, title, volumes, existing?.at, author, summary, tags.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }.distinct()); onChanged(); onDismiss() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "分卷保存失败" }
            finally { saving = false }
        }
    }) { Text(if (saving) "保存中…" else "保存 ${volumes.size} 卷") } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
    if (dissolve || delete) AlertDialog(onDismissRequest = { if (!saving) { dissolve = false; delete = false } }, title = { Text(if (delete) "删除整部书的本机文件？" else "拆分这本合订本？") }, text = {
        Text(if (delete) "所有分卷将移出书架，应用内副本和缓存会删除；外部原书、阅读进度与书签保留。" else "各卷回到书架，原文件、章节坐标、进度与书签保留。")
    }, confirmButton = { TextButton(enabled = !saving, onClick = {
        saving = true
        scope.launch {
            try {
                if (delete) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { library.collectionBooks().firstOrNull { it.id == existing?.id }?.let(library::remove) }
                else library.series.dissolve(existing!!.id)
                onChanged(); onDismiss()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "操作失败"; dissolve = false; delete = false }
            finally { saving = false }
        }
    }) { Text("确定") } }, dismissButton = { TextButton(onClick = { dissolve = false; delete = false }) { Text("取消") } })
}
