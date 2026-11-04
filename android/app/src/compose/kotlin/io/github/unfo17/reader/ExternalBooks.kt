package io.github.unfo17.reader

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal data class ExternalBookRequest(val id: String, val name: String, val canLink: Boolean, val canLocate: Boolean, val canMove: Boolean, val sourceName: String?, val sourceLocation: String?, val error: String?)
internal data class ExternalBookPreview(val id: String, val candidates: List<ImportCandidate>, val grant: Uri?, val copy: Boolean, val move: Boolean)

@Composable
internal fun ExternalOpenDialog(request: ExternalBookRequest, library: Library, onLocate: () -> Unit, onChanged: () -> Unit, onOpen: (Book) -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ExternalBookPreview?>(null) }
    var selected by remember { mutableStateOf<Set<ImportCandidate>>(emptySet()) }
    var error by remember { mutableStateOf<String?>(request.error) }
    var confirmMove by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Pair<Book, String>?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun import(selection: ExternalBookPreview, candidates: List<ImportCandidate>) {
        val accepted = mutableListOf<Book>(); val failures = mutableListOf<String>(); val failedCandidates = mutableSetOf<ImportCandidate>()
        for (candidate in candidates) {
            try { accepted += library.importCandidate(candidate, selection.copy, selection.grant) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { failures += org.mewx.wenku8_flutter.bookImportMessage(e); failedCandidates += candidate }
        }
        onChanged()
        if (failures.isNotEmpty()) { error = "已导入 ${accepted.size} 本；${failures.first()}"; selected = failedCandidates; return }
        if (selection.move && accepted.size == 1) {
            try { accepted[0] = library.moveExternal(request.id, accepted[0]) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { onChanged(); outcome = accepted[0] to (e.message ?: "书籍已导入，原文件状态未能确认"); return }
        }
        library.finishExternal(request.id)
        onChanged()
        if (accepted.size == 1) onOpen(accepted[0])
    }
    fun prepare(copy: Boolean, move: Boolean = false) { scope.launch {
        busy = true; error = null
        try {
            val selection = library.previewExternal(request, copy, move)
            if (selection.candidates.size == 1 && selection.candidates.first().entry == null) import(selection, selection.candidates)
            else { preview = selection; selected = selection.candidates.toSet() }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = org.mewx.wenku8_flutter.bookImportMessage(e) }
        finally { busy = false }
    } }
    val result = outcome
    if (result != null) {
        AlertDialog(onDismissRequest = { library.finishExternal(request.id); onChanged() }, title = { Text("书籍已导入") }, text = { Text(result.second) },
            confirmButton = { TextButton(onClick = { library.finishExternal(request.id); onChanged(); onOpen(result.first) }) { Text("阅读已导入书籍") } },
            dismissButton = { TextButton(onClick = { library.finishExternal(request.id); onChanged() }) { Text("返回书架") } })
        return
    }
    AlertDialog(onDismissRequest = { if (!busy) library.finishExternal(request.id) }, title = { Text(if (preview == null) "打开本地书籍" else "选择压缩包内书籍") }, text = {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(request.name)
            request.sourceName?.let { Text("来源：$it") }
            request.sourceLocation?.let { Text(it) }
            if (preview == null) {
                Text(if (request.canLink) "关联原书会保留原位置；复制后可由应用独立保存。" else "来源提供临时读取权限。可选择复制到应用，或通过系统选择器重新定位原书以长期关联。")
                TextButton(enabled = !busy && request.canLink && request.error == null, onClick = { prepare(false) }) { Text("关联并阅读") }
                TextButton(enabled = !busy && request.error == null, onClick = { prepare(true) }) { Text("复制并阅读") }
                TextButton(enabled = !busy, onClick = onLocate) { Text("重新定位原书") }
                if (request.canMove) TextButton(enabled = !busy, onClick = { confirmMove = true }) { Text("移动到应用") }
            } else {
                Text(if (preview!!.copy) "复制所选原包，保留原包中的图片资源。" else "关联原包，请保留它的位置。")
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(preview!!.candidates.size) { index -> val candidate = preview!!.candidates[index]
                        Row {
                            Checkbox(candidate in selected, { selected = if (it) selected + candidate else selected - candidate }, enabled = !busy)
                            Text(candidate.entry?.name ?: candidate.name)
                        }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        preview?.let { selection -> TextButton(enabled = !busy && selected.isNotEmpty(), onClick = { scope.launch {
            busy = true
            try { import(selection, selected.toList()) } finally { busy = false }
        } }) { Text("导入 ${selected.size} 本") } }
    }, dismissButton = { TextButton(enabled = !busy, onClick = { library.finishExternal(request.id) }) { Text("取消") } })
    if (confirmMove) AlertDialog(onDismissRequest = { confirmMove = false }, title = { Text("移动原书到应用？") }, text = { Text("完整复制并逐字节核对后，删除来源中的这一个文件。阅读记录保留。") },
        confirmButton = { TextButton(onClick = { confirmMove = false; prepare(true, true) }) { Text("确认移动原书") } },
        dismissButton = { TextButton(onClick = { confirmMove = false }) { Text("取消") } })
}
