package io.github.unfo17.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

enum class ReaderSearchScope(val label: String) { BOOK("全书"), VOLUME("当前卷"), CHAPTER("当前章节") }
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ReaderSearch(library: Library, current: Book, series: BookSeries?, chapterLocator: org.readium.r2.shared.publication.Locator?, onDismiss: () -> Unit, onNavigate: (PublicationSearch.Hit) -> Unit) {
    var query by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf("") }
    var searchScope by remember { mutableStateOf(ReaderSearchScope.BOOK) }
    var cursor by remember { mutableStateOf<PublicationSearch?>(null) }
    var hits by remember { mutableStateOf<List<PublicationSearch.Hit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    DisposableEffect(cursor) { val active = cursor; onDispose { active?.close() } }
    fun load(session: PublicationSearch) {
        job = scope.launch {
            busy = true
            try { val page = session.nextPage(); hits = hits + page.hits; finished = page.finished; error = null }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "搜索失败"; cursor = null }
            finally { busy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 680.dp), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("正文搜索", style = MaterialTheme.typography.titleLarge) }
            item { OutlinedTextField(query, { query = it }, label = { Text("搜索文字（最多 120 字符）") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true) }
            item { ChoiceMenu("范围", searchScope, ReaderSearchScope.entries.filter { it != ReaderSearchScope.VOLUME || series != null }.map { it to it.label }, enabled = !busy) { searchScope = it } }
            item { Row {
                TextButton(enabled = !busy && query.trim().length in 1..120, onClick = {
                    keyboard?.hide(); focus.clearFocus()
                    cursor?.close(); hits = emptyList(); finished = false; error = null; searched = query.trim()
                    val ids = if (searchScope == ReaderSearchScope.BOOK && series != null) series.volumes.map { it.bookId } else listOf(current.id)
                    val originals = library.books().associateBy { it.id }
                    val books = ids.mapNotNull { originals[it] }
                    if (books.size != ids.size) error = "部分分卷已移除，请重新导入后搜索"
                    else if (searchScope == ReaderSearchScope.CHAPTER && chapterLocator == null) error = "正文尚未定位，请稍后重试"
                    else { val session = PublicationSearch(library, books, searched, if (searchScope == ReaderSearchScope.CHAPTER) chapterLocator?.href?.toString() else null, if (searchScope == ReaderSearchScope.CHAPTER) chapterLocator else null); cursor = session; load(session) }
                }) { Text("搜索") }
                if (busy) TextButton(onClick = { job?.cancel(); cursor?.close(); cursor = null; error = "搜索已停止，已加载结果保留" }) { Text("停止搜索") }
            } }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (searched.isNotEmpty()) item { Text("“$searched” · 已加载 ${hits.size} 处" + if (finished) " · 搜索完成" else "") }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            items(hits.size, key = { index -> index }) { index ->
                val hit = hits[index]
                TextButton(onClick = { onNavigate(hit) }, modifier = Modifier.fillMaxWidth()) { Column {
                    Text("${index + 1}. ${hit.bookTitle} · ${hit.locator.title.orEmpty()}", style = MaterialTheme.typography.labelMedium)
                    Text(hit.snippet)
                } }
            }
            if (cursor != null && !finished && !busy) item { OutlinedButton(onClick = { cursor?.let(::load) }) { Text("继续加载结果") } }
            if (finished && hits.isEmpty()) item { Text("没有找到匹配的文字。") }
        }
    }
}
