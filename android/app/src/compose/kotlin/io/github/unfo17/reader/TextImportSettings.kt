package io.github.unfo17.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mewx.wenku8_flutter.LocalTextPublication
import org.mewx.wenku8_flutter.TextImportRules
import org.mewx.wenku8_flutter.TextRuleClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TextImportSettings(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("import-options", 0) }
    var rules by remember { mutableStateOf(TextImportRules.decode(preferences.getString("textRules", null))) }
    var sample by remember { mutableStateOf("第一卷 新世界\n第一章 开始\n这里是正文。\n第二章 后续") }
    var preview by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun check(save: Boolean) {
        busy = true
        scope.launch {
            try {
                val checked = rules.validate()
                val result = withContext(Dispatchers.IO) {
                    require(sample.length <= 20000) { "预览文字最多 20000 个字符" }
                    val lines = sample.split(Regex("\r\n?|\n")).map { it.trim() }.filter { it.isNotEmpty() && it.length <= 90 }
                    if (checked.customToc) TextRuleClient(context, checked).use { client -> lines.chunked(128).flatMap { batch -> client.classify(batch).zip(batch) }.filter { it.first != 0 } }
                    else lines.map { LocalTextPublication.defaultKind(it) to LocalTextPublication.headingCandidate(it) }.filter { it.first != 0 }
                }
                preview = result; error = null
                if (save) { check(withContext(Dispatchers.IO) { preferences.edit().putString("textRules", checked.json()).commit() }) { "导入设置保存失败" }; onDismiss() }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "目录规则处理失败" }
            finally { busy = false }
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 700.dp), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("TXT 导入设置", style = MaterialTheme.typography.titleLarge) }
            item { Text("设置随每本新导入的原书保存。书库中已有原书会继续使用它的目录设置，保留阅读坐标。") }
            item { ChoiceMenu("编码", rules.encoding, TextImportRules.ENCODINGS.map { it to if (it == "auto") "自动识别" else it }, enabled = !busy) { rules = rules.copy(encoding = it) } }
            item { Row(Modifier.fillMaxWidth().toggleable(rules.customToc, enabled = !busy, role = Role.Switch, onValueChange = { rules = rules.copy(customToc = it) })) { Text("自定义目录规则", Modifier.weight(1f)); Switch(rules.customToc, null, enabled = !busy) } }
            if (rules.customToc) {
                item { OutlinedTextField(rules.chapterRule, { rules = rules.copy(chapterRule = it) }, label = { Text("章节表达式") }, modifier = Modifier.fillMaxWidth(), enabled = !busy) }
                item { OutlinedTextField(rules.volumeRule, { rules = rules.copy(volumeRule = it) }, label = { Text("分卷表达式（可留空）") }, modifier = Modifier.fillMaxWidth(), enabled = !busy) }
                item { Text("仅匹配不超过 90 字符的整行候选；每条表达式最多 1024 字符。处理过慢会中止，原书保留。", style = MaterialTheme.typography.bodySmall) }
            }
            item { Row(Modifier.fillMaxWidth().toggleable(rules.autoTitles, enabled = !busy, role = Role.Switch, onValueChange = { rules = rules.copy(autoTitles = it) })) { Text("为无标题正文生成名称", Modifier.weight(1f)); Switch(rules.autoTitles, null, enabled = !busy) } }
            item { OutlinedTextField(sample, { sample = it }, label = { Text("目录预览文字") }, modifier = Modifier.fillMaxWidth(), minLines = 4, enabled = !busy) }
            item { TextButton(enabled = !busy, onClick = { check(false) }) { Text("预览目录") } }
            items(preview.size) { index -> val (kind, label) = preview[index]; Text((if (kind == 1) "卷 · " else "章 · ") + label) }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item { Row {
                TextButton(enabled = !busy, onClick = { check(true) }) { Text("保存导入设置") }
                TextButton(enabled = !busy, onClick = { rules = TextImportRules(); preview = emptyList(); error = null }) { Text("恢复默认") }
                TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
            } }
        }
    }
}
