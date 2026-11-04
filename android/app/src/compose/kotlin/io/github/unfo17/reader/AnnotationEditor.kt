package io.github.unfo17.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable fun AnnotationEditor(mark: ReadingMark, onDismiss: () -> Unit, onSave: (ReadingMark) -> Unit, onDelete: (() -> Unit)? = null, book: Book? = null) {
    var style by remember(mark.id) { mutableStateOf(mark.style) }
    var color by remember(mark.id) { mutableStateOf(mark.color) }
    var category by remember(mark.id) { mutableStateOf(mark.category) }
    var note by remember(mark.id) { mutableStateOf(mark.note) }
    var error by remember { mutableStateOf<String?>(null) }
    var quoteImage by remember { mutableStateOf(false) }
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("批注与摘录") }, text = {
        Column(Modifier.heightIn(max = 550.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(mark.quote.ifBlank { "位置书签" }, style = MaterialTheme.typography.bodyLarge)
            ChoiceMenu("样式", style, MarkStyle.entries.map { it to it.label }) { style = it }
            ChoiceMenu("颜色", color, MarkColor.entries.map { it to it.label }) { color = it }
            OutlinedTextField(category, { category = it }, label = { Text("批注分类") })
            OutlinedTextField(note, { note = it }, label = { Text("笔记") }, minLines = 3)
            val date = java.time.Instant.ofEpochMilli(mark.createdAt).atOffset(java.time.ZoneOffset.ofTotalSeconds(mark.createdOffsetMinutes * 60))
            Text(date.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm XXX")), style = MaterialTheme.typography.labelSmall)
            if (book != null && mark.quote.isNotBlank()) TextButton(onClick = { quoteImage = true }) { Text("生成摘录图片") }
            Row {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("摘录", mark.quote))
                }) { Text("复制摘录") }
                TextButton(onClick = { context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, mark.quote + if (note.isBlank()) "" else "\n\n$note"), "分享摘录")) }) { Text("分享") }
                if (onDelete != null) TextButton(onClick = { try { onDelete() } catch (e: Exception) { error = e.message } }) { Text("删除") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        try {
            val now = maxOf(System.currentTimeMillis(), mark.revision + 1)
            val changed = mark.copy(style = style, color = color, category = category.trim(), note = note,
                updatedAt = if (style != mark.style || color != mark.color || note != mark.note) now else mark.updatedAt,
                categoryAt = if (category.trim() != mark.category) now else mark.categoryAt)
            ReadingMark.from(changed.json()); onSave(changed)
        } catch (e: Exception) { error = e.message ?: "批注保存失败" }
    }) { Text("保存批注") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    if (quoteImage && book != null) QuotePreview(book, mark, { quoteImage = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AnnotationsPanel(library: Library, bookIds: Set<String>? = null, onDismiss: () -> Unit, onChanged: () -> Unit = {}, onNavigate: ((Book, ReadingMark) -> Unit)? = null, onNavigateLegacy: ((Book, String) -> Unit)? = null) {
    var revision by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Pair<Book, ReadingMark>?>(null) }
    val legacy = remember(revision) { library.records().filter { bookIds == null || it.id in bookIds }.flatMap { book -> book.bookmarks.map { book to it } } }
    val marks = remember(revision) { library.records().filter { bookIds == null || it.id in bookIds }.flatMap { book -> book.annotations.filterNot { it.deleted }.map { book to it } }.sortedByDescending { it.second.createdAt } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 650.dp), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("书签、批注和摘录", style = MaterialTheme.typography.titleLarge) }
            item { OutlinedTextField(query, { query = it }, label = { Text("搜索摘录、笔记或书名") }, modifier = Modifier.fillMaxWidth()) }
            item { ChoiceMenu("分类", category, listOf<String?>(null).map { it to "全部" } + marks.map { it.second.category }.distinct().sorted().map { it to it.ifBlank { "未分类" } }) { category = it } }
            val filtered = marks.filter { (book, mark) -> (category == null || mark.category == category) && (query.isBlank() || listOf(book.title, mark.quote, mark.note).any { it.contains(query, true) }) }
            if (filtered.isEmpty() && legacy.isEmpty()) item { Text("没有符合条件的批注。长按正文可添加划线、高亮或笔记。") }
            val oldMarks = legacy.filter { (book, raw) -> category == null && (query.isBlank() || book.title.contains(query, true) || raw.contains(query, true)) }
            items(oldMarks.size, key = { "legacy:${oldMarks[it].first.id}:$it" }) { index ->
                val (book, raw) = oldMarks[index]
                val location = runCatching { org.readium.r2.shared.publication.Locator.fromJSON(org.json.JSONObject(raw)) }.getOrNull()
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(book.title, style = MaterialTheme.typography.labelLarge)
                    TextButton(enabled = !book.removed && onNavigateLegacy != null, onClick = { onNavigateLegacy?.invoke(book, raw) }) { Text(location?.title ?: "旧版位置书签") }
                    TextButton(onClick = {
                        library.records().firstOrNull { it.id == book.id }?.let { current -> library.update(current.copy(bookmarks = current.bookmarks - raw)) }; revision++; onChanged()
                    }) { Text("删除书签") }
                } }
            }
            items(filtered.size, key = { filtered[it].first.id + ":" + filtered[it].second.id }) { index ->
                val (book, mark) = filtered[index]
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(book.title + if (book.removed) " · 原书已移除" else "", style = MaterialTheme.typography.labelLarge)
                        Text(mark.quote.ifBlank { org.readium.r2.shared.publication.Locator.fromJSON(org.json.JSONObject(mark.locator))?.title ?: "位置书签" }, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if (mark.note.isNotBlank()) Text(mark.note, maxLines = 3)
                        Row {
                            TextButton(onClick = { editing = book to mark }) { Text("编辑批注") }
                            if (!book.removed && onNavigate != null) TextButton(onClick = { onNavigate(book, mark) }) { Text("阅读原文") }
                        }
                    }
                }
            }
        }
    }
    editing?.let { (book, mark) -> AnnotationEditor(mark, { editing = null }, { updated ->
        library.saveAnnotation(book.id, updated, mark.revision); editing = null; revision++; onChanged()
    }, onDelete = { library.deleteAnnotation(book.id, mark.id, mark.revision); editing = null; revision++; onChanged() }, book = book) }
}
